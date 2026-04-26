package com.hybrid.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
    List<OutboxEvent> findByStatus(OutboxStatus status);
    long countByStatus(OutboxStatus status);
}