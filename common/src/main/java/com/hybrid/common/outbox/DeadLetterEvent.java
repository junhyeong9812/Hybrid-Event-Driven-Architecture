package com.hybrid.common.outbox;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_dlq")
public class DeadLetterEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long originalOutboxId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private String failureReason;
    private Instant movedAt = Instant.now();

    protected DeadLetterEvent() {}

    public static DeadLetterEvent from(OutboxEvent e) {
        DeadLetterEvent d = new DeadLetterEvent();
        d.originalOutboxId = e.getId();
        d.aggregateType = e.getAggregateType();
        d.aggregateId = e.getAggregateId();
        d.eventType = e.getEventType();
        d.payload = e.getPayload();
        d.failureReason = "max retry exceeded (" + e.getRetryCount() + ")";
        return d;
    }

    public OutboxEvent toRetryable() {
        return OutboxEvent.of(aggregateType, aggregateId, eventType, payload);
    }

    public Long getId() { return id; }
    public Long getOriginalOutboxId() { return originalOutboxId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getFailureReason() { return failureReason; }
    public Instant getMovedAt() { return movedAt; }
}