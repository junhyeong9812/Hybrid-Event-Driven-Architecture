package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    private final InboxRepository inbox;
    private final NotificationService notifications;
    private final MeterRegistry meter;

    public InboxConsumer(InboxRepository inbox,
                         NotificationService notifications,
                         MeterRegistry meter) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.meter = meter;
    }

    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        if (inbox.existsByMessageId(messageId)) {
            meter.counter("inbox.duplicate.count").increment();
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        try {
            inbox.save(InboxEvent.of(messageId, eventType, payload));
            notifications.process(eventType, payload);
            meter.counter("inbox.processed.count").increment();
        } catch (DataIntegrityViolationException dup) {
            meter.counter("inbox.duplicate.count").increment();
            log.info("concurrent duplicate skipped: {}", messageId);
        }
    }
}