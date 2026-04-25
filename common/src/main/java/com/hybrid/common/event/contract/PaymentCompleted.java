package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

public class PaymentCompleted extends AbstractDomainEvent {

    private final Long orderId;

    public PaymentCompleted(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    @Override public String eventType() { return "PaymentCompleted"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}