package com.hybrid.payment.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Payment 영속 추상.
 *
 * 자주 쓰는 파생 쿼리:
 *   - findByOrderId            : 단일 주문의 결제 조회
 *   - findByOrderIdAndStatus   : OrderRecoveryJob의 "COMPLETED 결제 있는지" 검사
 *   - findAllByOrderId         : 환불·재시도 등 같은 주문 결제 이력 전체
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
    List<Payment> findAllByOrderId(Long orderId);
}
