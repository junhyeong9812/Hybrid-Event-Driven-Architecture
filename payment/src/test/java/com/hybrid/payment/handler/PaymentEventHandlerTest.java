package com.hybrid.payment.handler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.payment.domain.PaymentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentEventHandlerTest {

    @Autowired InMemoryEventBus eventBus;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void OrderCreated_수신_시_Payment가_생성되고_PaymentCompleted가_발행된다() {
        List<PaymentCompleted> captured = new ArrayList<>();
        eventBus.subscribe(PaymentCompleted.class, captured::add);

        eventBus.publish(new OrderCreated(1L, BigDecimal.valueOf(1000)));

        await().atMost(2, SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).orderId()).isEqualTo(1L);
        });
    }
}
