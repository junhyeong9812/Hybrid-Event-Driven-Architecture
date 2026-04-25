package com.hybrid.notification.inbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbox")
public class InboxEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", unique = true, nullable = false)
    private String messageId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private String status = "PROCESSED";
    private Instant receivedAt = Instant.now();

    protected InboxEvent() {}

    public static InboxEvent of(String messageId, String eventType, String payload) {
        InboxEvent e = new InboxEvent();
        e.messageId = messageId;
        e.eventType = eventType;
        e.payload = payload;
        return e;
    }

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
}