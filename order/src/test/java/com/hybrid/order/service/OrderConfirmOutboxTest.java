package com.hybrid.order.service;

import java.math.BigDecimal;
import java.util.List;

import com.hybrid.common.outbox.OutboxEvent;
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * order 모듈의 OrderService.confirm이 도메인 변경과 outbox 기록을
 * 동일 트랜잭션에서 수행하는지 검증한다.
 *
 * payment 모듈이 classpath에 없으므로 Phase 1의 자연스러운 체인
 * (OrderCreated → Payment → PaymentCompleted → confirm) 대신
 * orderService.confirm을 직접 호출한다.
 */
class OrderConfirmOutboxTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OutboxRepository outboxRepository;

    @Test
    void 주문_확정_시_outbox에_OrderConfirmed_이벤트가_저장된다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
        orderService.confirm(orderId);

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent e = events.get(0);
        assertThat(e.getAggregateType()).isEqualTo("Order");
        assertThat(e.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(e.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void 주문_확정_트랜잭션_롤백_시_outbox에도_기록되지_않는다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
        long before = outboxRepository.count();

        assertThatThrownBy(() -> orderService.confirmWithForcedRollback(orderId))
                .isInstanceOf(RuntimeException.class);

        assertThat(outboxRepository.count()).isEqualTo(before);
    }
}
