package com.hybrid.notification.service;

import java.time.Duration;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.notification.support.EmailSenderStub;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(EmailSenderStub.Config.class)
class NotificationCircuitBreakerTest extends KafkaIntegrationTestBase {

    @Autowired NotificationService notificationService;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired EmailSenderStub emailSenderStub;

    @AfterEach
    void resetStubAndCircuit() {
        emailSenderStub.reset();
        circuitBreakerRegistry.circuitBreaker("email").reset();
    }

    @Test
    void EmailSender가_연속_실패하면_Circuit이_OPEN되고_빠르게_실패한다() {
        emailSenderStub.alwaysFail();

        for (int i = 0; i < 10; i++) {
            try { notificationService.process("OrderConfirmed", "{}"); }
            catch (Exception ignored) {}
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("email");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        assertThatThrownBy(() -> notificationService.process("OrderConfirmed", "{}"))
                .isInstanceOf(CallNotPermittedException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(50);   // 빠른 실패
    }

    @Test
    void OPEN_상태_이후_일정_시간이_지나면_HALF_OPEN으로_전환된다() throws InterruptedException {
        emailSenderStub.alwaysFail();
        for (int i = 0; i < 10; i++) {
            try { notificationService.process("OrderConfirmed", "{}"); }
            catch (Exception ignored) {}
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("email");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(11_000);   // waitDurationInOpenState(10s) + 여유

        emailSenderStub.alwaysSucceed();
        try { notificationService.process("OrderConfirmed", "{}"); }
        catch (Exception ignored) {}

        assertThat(cb.getState()).isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
    }
}