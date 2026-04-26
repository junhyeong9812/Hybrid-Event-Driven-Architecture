package com.hybrid.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
    List<OutboxEvent> findByStatus(OutboxStatus status);
    long countByStatus(OutboxStatus status);
    @Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("batchSize") int batchSize);
}