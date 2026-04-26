package com.hybrid.common.outbox;

/**
 * outbox 행의 라이프사이클 상태.
 *
 *   PENDING     ──Relay 발행 성공──►  PUBLISHED  ──7일 후 Cleaner 삭제──►  (DELETE)
 *      │
 *      └──MAX_RETRY 초과──►  DEAD_LETTER ──Sweeper──►  outbox_dlq 테이블로 이관
 *                                       └──admin retry──►  PENDING으로 복귀
 *
 * 상태별 인덱스 / 쿼리:
 *   - PENDING은 부분 인덱스(idx_outbox_pending)로 빠르게 폴링.
 *   - DEAD_LETTER는 Sweeper가 60초마다 스캔.
 */
public enum OutboxStatus {
    // 발행 대기 — 새로 INSERT된 행의 기본 상태.
    PENDING,
    // Kafka 발행 성공 — markPublished()로 전이, publishedAt이 채워짐.
    PUBLISHED,
    // 재시도 한계 초과 — 정상 흐름에서 자동 처리 불가, 운영자 개입 필요.
    DEAD_LETTER
}
