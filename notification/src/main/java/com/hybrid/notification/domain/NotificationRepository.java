package com.hybrid.notification.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Notification 영속 추상.
 *
 *   - findByOrderId       : 주문에 대한 알림 1건 조회 (단일 채널 검증 시)
 *   - findAllByOrderId    : 같은 주문의 모든 채널 알림 (EMAIL + PUSH + SMS) 조회
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByOrderId(Long orderId);
    List<Notification> findAllByOrderId(Long orderId);
}
