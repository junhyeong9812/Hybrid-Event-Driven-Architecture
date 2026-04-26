package com.hybrid.payment.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.payment.service.PaymentService;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment 도메인의 EventBus 구독자 — OrderCreated → 결제 트리거.
 *
 * 모듈 경계:
 *   OrderCreated를 common.event.contract에서 import — order 모듈 직접 의존 없음.
 *   payment / order 양쪽이 같은 contract만 보고 통신.
 */
@Component
public class PaymentEventHandler {

    private final PaymentService paymentService;

    public PaymentEventHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 빈 초기화 시 EventBus에 구독 등록.
     * @PostConstruct + @Autowired InMemoryEventBus — Spring이 EventBus 인스턴스 주입.
     */
    @PostConstruct
    void register(@Autowired InMemoryEventBus bus) {
        bus.subscribe(OrderCreated.class, this::onOrderCreated);
    }

    /**
     * OrderCreated 수신 시 결제 처리.
     * @Transactional — 결제 처리가 별도 트랜잭션 (afterCommit 시점이라 외부 트랜잭션 없음).
     */
    @Transactional
    public void onOrderCreated(OrderCreated event) {
        paymentService.process(event.orderId(), event.amount());
    }
}
