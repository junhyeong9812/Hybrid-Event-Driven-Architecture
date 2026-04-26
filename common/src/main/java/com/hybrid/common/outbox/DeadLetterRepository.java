package com.hybrid.common.outbox;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * outbox_dlq 테이블 영속 추상.
 *
 * 핵심 메서드 — findByOriginalOutboxId:
 *   같은 비즈니스 사실에 대한 outbox / dlq 행 사이 매핑 추적.
 *   "이 사건이 DLQ로 갔는지" 운영자가 확인할 때 사용.
 */
public interface DeadLetterRepository extends JpaRepository<DeadLetterEvent, Long> {
    // 원본 outbox.id로 DLQ 행 조회 — 운영 / 테스트에서 이력 추적.
    Optional<DeadLetterEvent> findByOriginalOutboxId(Long outboxId);
}
