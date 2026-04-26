package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class Phase1E2ETest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void 주문_생성부터_확정까지_이벤트_체인이_동작한다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        await().atMost(3, SECONDS).untilAsserted(() -> {
            assertThat(orderRepository.findById(orderId).orElseThrow().status())
                    .isEqualTo(OrderStatus.CONFIRMED);
            assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().status())
                    .isEqualTo(PaymentStatus.COMPLETED);
        });
    }

    @Test
    void 주문_생성_트랜잭션_롤백_시_Payment는_만들어지지_않는다() {
        assertThatThrownBy(() ->
                orderService.createWithForcedRollback(new CreateOrderCommand(1L, BigDecimal.TEN)))
                .isInstanceOf(RuntimeException.class);

        assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    void 동시에_100개_주문을_생성해도_각각_결제가_완료된다() throws Exception {
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++)
            futures.add(pool.submit(() ->
                    orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN))));

        Set<Long> ids = new HashSet<>();
        for (var f : futures) ids.add(f.get());

        await().atMost(10, SECONDS).untilAsserted(() -> {
            long confirmed = orderRepository.countByStatus(OrderStatus.CONFIRMED);
            assertThat(confirmed).isEqualTo(n);
            assertThat(ids).hasSize(n);
        });
    }
}