package com.hybrid.order.event;

import java.math.BigDecimal;

import com.hybrid.common.event.AbstractDomainEvent;

public class OrderCreated extends AbstractDomainEvent {

    private final Long orderId;
    private final BigDecimal amount;

    public OrderCreated(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public Long orderId() { return orderId; }
    public BigDecimal amount() { return amount; }

    @Override public String eventType() { return "OrderCreated"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}