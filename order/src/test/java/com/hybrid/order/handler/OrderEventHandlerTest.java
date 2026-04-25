package com.hybrid.order.handler;

import java.math.BigDecimal;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderEventHandlerTest {

    @Autowired InMemoryEventBus eventBus;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;

    @Test
    void PaymentCompleted_수신_시_Order_상태가_CONFIRMED로_변경된다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));

        eventBus.publish(new PaymentCompleted(orderId));

        await().atMost(2, SECONDS).untilAsserted(() -> {
            Order o = orderRepository.findById(orderId).orElseThrow();
            assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        });
    }
}