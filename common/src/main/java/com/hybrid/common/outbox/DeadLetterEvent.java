package com.hybrid.common.outbox;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 영구 실패한 outbox 행을 보관하는 별도 테이블의 엔티티.
 *
 * 왜 별도 테이블인가:
 *   - outbox는 정상 흐름의 데이터, DLQ는 운영자 검토 대상 — 분리해야 운영 쿼리 명확.
 *   - outbox_dlq 행은 자동 정리 안 됨 (운영자 의사결정 필요).
 *   - 알람 / 모니터링은 outbox.deadletter.count 게이지로 별도.
 *
 * 라이프사이클:
 *   outbox에서 status=DEAD_LETTER인 행을 DeadLetterSweeper가 60초마다 이관.
 *   운영자가 admin 엔드포인트로 toRetryable() → outbox PENDING으로 복귀시킬 수 있음.
 */
@Entity
@Table(name = "outbox_dlq")
public class DeadLetterEvent {

    // 신규 PK — outbox와 다른 시퀀스.
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 원본 outbox 행의 id — 이력 추적용. 같은 비즈니스 사실에 대한 두 개의 PK 보존.
    private Long originalOutboxId;
    // 도메인 식별 — outbox에서 그대로 복사.
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    // 페이로드도 그대로 보존 — 재시도 시 재사용.
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    // 실패 사유 — 운영자가 DLQ를 검토할 때 단서.
    private String failureReason;
    // 이관 시각 — 얼마나 오래 DLQ에 있었는지 추적.
    private Instant movedAt = Instant.now();

    protected DeadLetterEvent() {}

    /**
     * outbox 행 → DLQ 행 변환 (Sweeper가 사용).
     * 모든 데이터를 그대로 복사하고 실패 사유만 추가.
     */
    public static DeadLetterEvent from(OutboxEvent e) {
        DeadLetterEvent d = new DeadLetterEvent();
        d.originalOutboxId = e.getId();
        d.aggregateType = e.getAggregateType();
        d.aggregateId = e.getAggregateId();
        d.eventType = e.getEventType();
        d.payload = e.getPayload();
        // 표준 사유 — 운영 시 실제 예외 메시지를 함께 저장하도록 확장 가능.
        d.failureReason = "max retry exceeded (" + e.getRetryCount() + ")";
        return d;
    }

    /**
     * 운영자 수동 재시도 시 호출 — DLQ 행 → 새 outbox PENDING 행.
     * 새로 retryCount=0으로 시작하므로 다시 발행 시도 가능.
     */
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
