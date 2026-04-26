package com.hybrid.order.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    long countByStatus(OrderStatus status);
    List<Order> findByStatus(OrderStatus status);
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant before);
}
