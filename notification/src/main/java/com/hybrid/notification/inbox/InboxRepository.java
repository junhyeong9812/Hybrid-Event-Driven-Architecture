package com.hybrid.notification.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByMessageId(String messageId);
}