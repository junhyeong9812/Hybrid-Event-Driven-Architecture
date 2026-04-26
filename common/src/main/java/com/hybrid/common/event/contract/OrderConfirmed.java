package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

/**
 * "주문이 확정됐다"는 사실. 결제 완료 후 발행됨.
 *
 * Phase 2부터 outbox에 INSERT되어 Kafka로 발행 → Notification이 알림 발송 트리거.
 * "내부 인메모리 + 외부 Kafka" 두 경로 모두로 흐르는 이벤트.
 */
public class OrderConfirmed extends AbstractDomainEvent {

    // 어느 주문이 확정됐는가.
    private final Long orderId;

    public OrderConfirmed(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    // Kafka로 나갈 때 헤더로 들어가는 타입 식별자.
    @Override public String eventType() { return "OrderConfirmed"; }
    // 같은 주문의 이벤트들이 같은 Kafka 파티션에 묶이도록.
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
