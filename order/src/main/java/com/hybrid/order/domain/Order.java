package com.hybrid.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Order 애그리거트 — 주문 도메인의 진실의 원천.
 *
 * 도메인 규칙:
 *   - 금액은 양수여야 한다.
 *   - 상태는 정해진 전이만 가능 (CREATED → CONFIRMED 등).
 *   - 외부에서 setter로 상태 직접 변경 금지 — 메서드(create, confirm 등)로만.
 *
 * JPA 매핑:
 *   - 외래키를 두지 않음 (Payment와의 분리 가능성을 위해).
 *   - status는 EnumType.STRING으로 — DB에서 사람이 읽기 좋고 enum 값 변경에 안전.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 어떤 고객의 주문인지 — 외래키는 두지 않음 (학습 단계 단순화).
    private Long customerId;
    // BigDecimal — 통화 금액은 절대 double로 표현 안 함 (반올림 오류).
    private BigDecimal amount;
    // 라이프사이클 상태. 상태 전이는 메서드를 통해서만.
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    // 생성 시각 — OrderRecoveryJob의 60초 임계 판단 기준.
    private Instant createdAt;

    // JPA reflection용 — 외부에서 호출 금지.
    protected Order() {}

    /**
     * 정적 팩토리 — 도메인 불변식 검증 후 객체 생성.
     * 생성자 노출 대신 이 메서드만 → 잘못된 상태로 객체가 만들어질 수 없음.
     */
    public static Order create(Long customerId, BigDecimal amount) {
        // 도메인 규칙 강제 — 금액은 양수.
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Order o = new Order();
        o.customerId = customerId;
        o.amount = amount;
        o.status = OrderStatus.CREATED;
        o.createdAt = Instant.now();
        return o;
    }

    /**
     * 주문 확정 — 결제 완료 후 호출됨.
     * 상태 전이 규칙: CREATED인 경우만 CONFIRMED 가능.
     * 이미 CONFIRMED / CANCELLED인 경우 호출하면 IllegalStateException.
     */
    public void confirm() {
        if (status != OrderStatus.CREATED)
            throw new IllegalStateException("cannot confirm from " + status);
        status = OrderStatus.CONFIRMED;
    }

    // 표준 getter들 — Repository / Controller / 테스트가 사용.
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    // 다른 getter와 달리 status() 짧게 — 도메인 표현으로 자주 호출됨.
    public OrderStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
