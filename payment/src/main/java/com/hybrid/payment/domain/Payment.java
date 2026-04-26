package com.hybrid.payment.domain;

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
 * Payment 애그리거트 — 결제 도메인의 진실의 원천.
 *
 * 도메인 규칙:
 *   - 금액은 양수.
 *   - PENDING에서만 complete/fail로 전이 가능.
 *   - orderId 외래키 없음 — Order와의 분리 가능성을 위해 SQL 레벨 결합 회피.
 *
 * Order 패턴과 거의 동일 — 도메인 분리 사상의 일관성.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 어느 주문의 결제인지 — 외래키 아님, 단순 long 참조.
    private Long orderId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private Instant createdAt;

    protected Payment() {}

    /** 정적 팩토리 — 결제 요청 시점에 PENDING으로 시작. */
    public static Payment request(Long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Payment p = new Payment();
        p.orderId = orderId;
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        return p;
    }

    /** 결제 성공 시 호출 — PENDING → COMPLETED. */
    public void complete() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot complete from " + status);
        status = PaymentStatus.COMPLETED;
    }

    /** 결제 실패 시 호출 — PENDING → FAILED. 외부 결제사 거절 등. */
    public void fail() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot fail from " + status);
        status = PaymentStatus.FAILED;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
