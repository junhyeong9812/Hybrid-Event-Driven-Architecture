package com.hybrid.payment.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.payment.service.PaymentService;


import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventHandler {

    private final PaymentService paymentService;

    public PaymentEventHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostConstruct
    void register(@Autowired InMemoryEventBus bus) {
        bus.subscribe(OrderCreated.class, this::onOrderCreated);
    }

    @Transactional
    public void onOrderCreated(OrderCreated event) {
        paymentService.process(event.orderId(), event.amount());
    }
}