package com.hybrid.common.outbox;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetterEvent, Long> {
    Optional<DeadLetterEvent> findByOriginalOutboxId(Long outboxId);
}