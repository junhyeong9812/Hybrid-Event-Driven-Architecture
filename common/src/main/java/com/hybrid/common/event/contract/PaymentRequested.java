package com.hybrid.common.event.contract;

import java.math.BigDecimal;

import com.hybrid.common.event.AbstractDomainEvent;

public class PaymentRequested extends AbstractDomainEvent {

    private final Long orderId;
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