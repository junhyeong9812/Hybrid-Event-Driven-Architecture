package com.hybrid.notification.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Inbox 영속 추상 — 핵심은 existsByMessageId.
 *
 * existsByMessageId는 멱등성 1차 방어선:
 *   - SELECT EXISTS(SELECT 1 FROM inbox WHERE message_id = ?)
 *   - UNIQUE 인덱스 덕분에 빠름.
 *   - 동시 요청이 둘 다 통과해도 INSERT 시점에 DB가 중복 차단 (2차 방어선).
 */
public interface InboxRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByMessageId(String messageId);
}
