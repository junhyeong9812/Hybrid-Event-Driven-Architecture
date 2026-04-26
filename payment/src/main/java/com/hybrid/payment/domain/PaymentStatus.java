package com.hybrid.payment.domain;

/**
 * 결제 라이프사이클 상태.
 *
 *   PENDING ──complete()──► COMPLETED
 *           ──fail()────► FAILED
 *           ──refund()──► REFUNDED (미구현)
 *
 * 상태 전이는 Payment 도메인 메서드를 통해서만. 임의 setter 노출 금지.
 */
public enum PaymentStatus {
    PENDING, COMPLETED, FAILED, REFUNDED
}
