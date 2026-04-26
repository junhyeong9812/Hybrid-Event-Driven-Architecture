package com.hybrid.notification.support;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.hybrid.notification.service.EmailGateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * NotificationCircuitBreakerTest 등에서 외부 메일 발송 실패를 결정적으로 시뮬레이션하는 스텁.
 *
 * EmailGateway를 구현하므로 production EmailGateway(LoggingEmailGateway)를 @Primary로 대체.
 * alwaysFail()/alwaysSucceed()로 동작 전환 가능.
 *
 * 이름은 EmailSenderStub이지만 실제로는 EmailGateway 자리에 들어간다 — 실패 주입 지점이 gateway이기 때문.
 */
public class EmailSenderStub implements EmailGateway {

    private final AtomicBoolean alwaysFail = new AtomicBoolean(false);
    private final AtomicInteger callCount = new AtomicInteger();

    public void alwaysFail() { alwaysFail.set(true); }
    public void alwaysSucceed() { alwaysFail.set(false); }

    public void reset() {
        alwaysFail.set(false);
        callCount.set(0);
    }

    public int callCount() { return callCount.get(); }

    @Override
    public void deliver(String to, String content) {
        callCount.incrementAndGet();
        if (alwaysFail.get()) {
            throw new RuntimeException("email gateway stub: alwaysFail");
        }
        // 성공 시엔 아무 동작도 안 함 (테스트라 진짜 발송할 필요 없음)
    }

    /** 테스트가 @Import로 끌어들여 production 빈을 대체하는 설정. */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public EmailSenderStub emailSenderStub() {
            return new EmailSenderStub();
        }

        @Bean
        @Primary
        public EmailGateway stubGateway(EmailSenderStub stub) {
            return stub;
        }
    }
}
