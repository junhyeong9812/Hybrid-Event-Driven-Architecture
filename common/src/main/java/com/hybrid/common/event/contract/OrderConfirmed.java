package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

public class OrderConfirmed extends AbstractDomainEvent {

    private final Long orderId;

    public OrderConfirmed(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    @Override public String eventType() { return "OrderConfirmed"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
