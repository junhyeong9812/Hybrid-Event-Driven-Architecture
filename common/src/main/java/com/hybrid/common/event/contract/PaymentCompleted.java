package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

/**
 * "결제가 완료됐다"는 사실 — Payment → Order 방향 이벤트.
 *
 * Order의 OrderEventHandler가 이를 구독해 주문을 CONFIRMED로 전이시킴.
 * payment 모듈이 발행하지만 order 모듈도 import하므로 contract 패키지에 위치.
 */
public class PaymentCompleted extends AbstractDomainEvent {

    private final Long orderId;

    public PaymentCompleted(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    @Override public String eventType() { return "PaymentCompleted"; }
    // 결제는 주문에 종속이라 aggregateId는 orderId로 — 같은 주문의 이벤트 흐름이 한 파티션.
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
