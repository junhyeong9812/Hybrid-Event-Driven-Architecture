package com.hybrid.common.event.contract;

import java.math.BigDecimal;

// AbstractDomainEvent로 eventId/occurredAt 자동 캐싱.
import com.hybrid.common.event.AbstractDomainEvent;

/**
 * "주문이 생성됐다"는 사실을 나타내는 도메인 이벤트.
 *
 * 위치 결정:
 *   - common.event.contract — Order/Payment/Notification이 모두 보는 통신 계약.
 *   - 이 자리가 핵심 — 도메인끼리 직접 import 금지를 강제하기 위함.
 *
 * Payment의 PaymentEventHandler가 이 이벤트를 구독해 결제를 시작한다.
 */
public class OrderCreated extends AbstractDomainEvent {

    // 비즈니스 데이터 — 발생한 주문의 ID와 금액.
    // 외부 컨슈머는 이 두 필드만 보고도 결제를 트리거할 수 있음.
    private final Long orderId;
    private final BigDecimal amount;

    public OrderCreated(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    // record가 아닌 일반 클래스인 이유: AbstractDomainEvent를 상속해야 하기 때문.
    // record는 다른 클래스를 상속할 수 없음.
    public Long orderId() { return orderId; }
    public BigDecimal amount() { return amount; }

    // 이벤트 이름 — Kafka 헤더 / 로깅에서 사용.
    @Override public String eventType() { return "OrderCreated"; }
    // Kafka 파티션 키 — 같은 주문의 이벤트가 같은 파티션에 모이도록.
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
