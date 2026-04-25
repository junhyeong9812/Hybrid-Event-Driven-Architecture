package com.hybrid.order.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.order.service.OrderService;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class OrderEventHandler {

    private final OrderService orderService;

    public OrderEventHandler(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostConstruct
    void register(InMemoryEventBus bus) {
        bus.subscribe(PaymentCompleted.class, this::onPaymentCompleted);
    }

    public void onPaymentCompleted(PaymentCompleted event) {
        orderService.confirm(event.orderId());
    }
}