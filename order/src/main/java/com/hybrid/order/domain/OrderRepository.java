package com.hybrid.order.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Order 영속 추상.
 *
 * Spring Data JPA의 파생 쿼리 활용 — 메서드 이름이 SQL을 자동 생성.
 *   - countByStatus → SELECT COUNT(*) WHERE status = ?
 *   - findByStatus  → SELECT * WHERE status = ?
 *   - findByStatusAndCreatedAtBefore → SELECT * WHERE status = ? AND created_at < ?
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
    // 운영 메트릭 / 통계용.
    long countByStatus(OrderStatus status);
    // ArchUnit / 운영 진단용.
    List<Order> findByStatus(OrderStatus status);
    // OrderRecoveryJob 전용 — "60초 이상 PAYMENT_PENDING으로 묶인 주문" 식별.
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant before);
}
