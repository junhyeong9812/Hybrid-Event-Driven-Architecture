package com.hybrid.notification.kafka;

import com.hybrid.notification.inbox.InboxConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationKafkaListener {

    private final InboxConsumer inboxConsumer;

    public NotificationKafkaListener(InboxConsumer inboxConsumer) {
        this.inboxConsumer = inboxConsumer;
    }

    @KafkaListener(topics = "order-events", groupId = "notification")
    public void onMessage(ConsumerRecord<String,String> record) {
        String messageId = header(record, "messageId");
        String eventType = header(record, "eventType");
        inboxConsumer.consume(messageId, eventType, record.value());
    }

    private String header(ConsumerRecord<?,?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }
}