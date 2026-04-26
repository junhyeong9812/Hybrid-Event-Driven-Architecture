package com.hybrid.order.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.order.service.OrderService;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 인메모리 EventBus의 PaymentCompleted 구독자 — Order 도메인의 외부 알림 수신.
 *
 * 흐름:
 *   Payment가 결제 완료 → InMemoryEventBus.publish(PaymentCompleted)
 *     → OrderEventHandler.onPaymentCompleted (이 클래스)
 *     → OrderService.confirm으로 위임 → Order 상태 CONFIRMED + outbox INSERT
 *
 * 모듈 경계:
 *   PaymentCompleted를 common.event.contract에서 import — payment 모듈 직접 의존 없음.
 */
@Component
public class OrderEventHandler {

    private final OrderService orderService;

    public OrderEventHandler(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 빈 초기화 후 EventBus에 자기 자신을 구독자로 등록.
     * @PostConstruct 시점에 InMemoryEventBus를 매개변수로 받음 (Spring 메서드 주입).
     */
    @PostConstruct
    void register(InMemoryEventBus bus) {
        bus.subscribe(PaymentCompleted.class, this::onPaymentCompleted);
    }

    /**
     * 결제 완료 이벤트 수신 시 호출.
     * OrderService.confirm은 자체 @Transactional이라 새 트랜잭션 안에서 실행.
     */
    public void onPaymentCompleted(PaymentCompleted event) {
        orderService.confirm(event.orderId());
    }
}
