package com.hybrid.order.domain;

/**
 * 주문의 라이프사이클 상태.
 *
 *   CREATED          (주문 생성됨)
 *      │
 *      ▼ Order.confirm() — 결제 완료 후
 *   CONFIRMED        (주문 확정 — Outbox 이벤트 발행 트리거)
 *
 *   CREATED ──Order.cancel() (미구현)──► CANCELLED
 *
 * PAYMENT_PENDING은 결제 처리 중인 임시 상태로, JVM 크래시 시
 * OrderRecoveryJob이 이 상태의 주문을 검사해 복구한다.
 */
public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED
}
