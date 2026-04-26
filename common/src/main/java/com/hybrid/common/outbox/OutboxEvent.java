package com.hybrid.common.outbox;

import java.time.Instant;

// JPA 매핑.
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// PostgreSQL JSONB 타입 매핑을 위한 Hibernate 어노테이션.
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outbox 패턴의 핵심 엔티티 — DB와 메시지 큐 사이 이중 쓰기 함정을 푸는 그릇.
 *
 * 동작:
 *   1. 도메인 트랜잭션 안에서 OutboxEvent를 INSERT (PENDING 상태).
 *   2. 별도 Relay가 PENDING 행을 폴링해 Kafka로 발행.
 *   3. 발행 성공 시 PUBLISHED, 실패 시 retryCount++, 한계 초과 시 DEAD_LETTER.
 *
 * 도메인 무관:
 *   aggregateType("Order"/"Payment"/"Notification")으로 도메인 구분.
 *   하나의 outbox 테이블이 모든 도메인의 메시지를 받아낸다.
 */
@Entity @Table(name = "outbox")
public class OutboxEvent {

    // 자동 증가 PK — Kafka 메시지의 messageId로도 사용됨 (Inbox에서 중복 판별).
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    // 어느 도메인의 이벤트인지 ("Order", "Payment" 등) — 운영 분석용.
    private String aggregateType;
    // 그 도메인의 어느 인스턴스인지 (orderId 등) — Kafka 파티션 키로 사용.
    private String aggregateId;
    // 이벤트 타입명 ("OrderConfirmed" 등) — Kafka 헤더로 전달.
    private String eventType;

    // JSON 직렬화된 페이로드.
    // PostgreSQL JSONB 컬럼 — 향후 운영 쿼리에서 payload->>'orderId' 같은 추출 가능.
    @JdbcTypeCode(SqlTypes.JSON) private String payload;

    // 라이프사이클 상태. 기본값 PENDING — INSERT 직후엔 항상 PENDING.
    @Enumerated(EnumType.STRING) private OutboxStatus status = OutboxStatus.PENDING;

    // 발행 실패 누적 횟수 — MAX_RETRY 초과 시 DEAD_LETTER로 전이.
    private int retryCount;

    // INSERT 시각. 부분 인덱스의 정렬 키 — 오래된 PENDING부터 처리.
    private Instant createdAt = Instant.now();
    // 발행 완료 시각. PUBLISHED 전이 시 채워짐. Cleaner의 7일 기준이 이 컬럼.
    private Instant publishedAt;

    // JPA가 reflection으로 사용 — 외부에서 호출 금지.
    public OutboxEvent() {}

    /**
     * 정적 팩토리 — 도메인 코드가 OutboxRepository.save 전에 호출.
     * payload는 호출자가 미리 JSON으로 직렬화한 문자열.
     */
    public static OutboxEvent of(String type, String aggId, String eventType, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = type;
        e.aggregateId = aggId;
        e.eventType = eventType;
        e.payload = payload;
        return e;
    }

    /** Relay가 Kafka 발행 성공 후 호출 — PUBLISHED 전이 + 시각 기록. */
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }
    /** retryCount가 MAX_RETRY 도달 시 호출 — DEAD_LETTER 전이. */
    public void markDeadLetter() { this.status = OutboxStatus.DEAD_LETTER; }
    /** 발행 실패 시 호출 — 재시도 횟수 누적. */
    public void incrementRetry() { this.retryCount++; }

    // 표준 getter들 — Repository / Relay / Sweeper / Test가 사용.
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
