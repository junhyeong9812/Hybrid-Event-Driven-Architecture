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

/**
 * Inbox 테이블의 JPA 매핑 — 멱등성 처리의 영속 모델.
 *
 * 핵심 컬럼 — message_id:
 *   - UNIQUE 인덱스가 걸려있음 (V3__inbox.sql).
 *   - 같은 messageId의 두 번째 INSERT는 DataIntegrityViolationException → 중복 차단.
 *   - Outbox 행의 id가 그대로 messageId로 사용됨 (Kafka 헤더로 전달).
 *
 * payload는 audit 용도 — 처리 후 운영 진단을 위해 남김.
 */
@Entity
@Table(name = "inbox")
public class InboxEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // UNIQUE 제약은 V3__inbox.sql의 idx_inbox_message_id 인덱스로 강제.
    // JPA의 unique=true도 함께 둠 — 스키마 검증과 의도 표현 모두에 명시적.
    @Column(name = "message_id", unique = true, nullable = false)
    private String messageId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    // 현재는 PROCESSED 고정 — 향후 ERROR 같은 상태 추가 시 분기.
    private String status = "PROCESSED";
    private Instant receivedAt = Instant.now();

    protected InboxEvent() {}

    /** InboxConsumer가 사용하는 정적 팩토리. */
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
