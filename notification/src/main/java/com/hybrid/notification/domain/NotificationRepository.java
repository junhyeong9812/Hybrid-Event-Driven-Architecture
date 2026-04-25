package com.hybrid.notification.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByOrderId(Long orderId);
    List<Notification> findAllByOrderId(Long orderId);
}
