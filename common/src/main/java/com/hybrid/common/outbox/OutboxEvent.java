package com.hybrid.common.outbox;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON) private String payload;
    @Enumerated(EnumType.STRING) private OutboxStatus status = OutboxStatus.PENDING;
    private int retryCount;
    private Instant createdAt = Instant.now();
    private Instant publishedAt;

    public static OutboxEvent of(String type, String aggId, String eventType, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = type; e.aggregateId = aggId;
        e.eventType = eventType; e.payload = payload;
        return e;
    }

    public void markPublished() { this.status = OutboxStatus.PUBLISHED; this.publishedAt = Instant.now(); }
    public void markDeadLetter() { this.status = OutboxStatus.DEAD_LETTER; }
    public void incrementRetry() { this.retryCount++; }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}