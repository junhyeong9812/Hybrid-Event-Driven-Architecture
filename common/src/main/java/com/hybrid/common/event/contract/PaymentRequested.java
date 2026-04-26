package com.hybrid.common.event.contract;

import java.math.BigDecimal;

import com.hybrid.common.event.AbstractDomainEvent;

/**
 * "결제 요청이 시작됐다"는 사실 — 운영 추적 / 감사 용도 이벤트.
 *
 * 현재는 Phase 1 흐름에서 직접 사용되진 않지만, 운영 관측을 위한 명시적 이벤트로 보존.
 * 향후 결제 처리 단계가 길어지거나 외부 결제사 연동 시 의미 있는 사건이 됨.
 */
public class PaymentRequested extends AbstractDomainEvent {

    // 어느 주문에 대한 결제 요청인지.
    private final Long orderId;
    // 결제 금액 — payment 도메인이 외부 결제사로 보낼 때 사용.
    private final BigDecimal amount;

    public PaymentRequested(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public Long orderId() { return orderId; }
    public BigDecimal amount() { return amount; }

    @Override public String eventType() { return "PaymentRequested"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
