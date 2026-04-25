package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    private final InboxRepository inbox;
    private final NotificationService notifications;

    public InboxConsumer(InboxRepository inbox, NotificationService notifications) {
        this.inbox = inbox;
        this.notifications = notifications;
    }

    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        if (inbox.existsByMessageId(messageId)) {
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        inbox.save(InboxEvent.of(messageId, eventType, payload));
        notifications.process(eventType, payload);
    }
}