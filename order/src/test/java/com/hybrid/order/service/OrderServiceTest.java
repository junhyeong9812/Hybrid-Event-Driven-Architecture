package com.hybrid.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.order.event.OrderCreated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OrderServiceTest {

    @Autowired OrderService orderService;
    @Autowired InMemoryEventBus eventBus;

    @Test
    void 주문_생성_시_OrderCreated_이벤트가_발행된다() {
        List<OrderCreated> captured = new ArrayList<>();
        eventBus.subscribe(OrderCreated.class, captured::add);

        Long id = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).orderId()).isEqualTo(id);
    }
}