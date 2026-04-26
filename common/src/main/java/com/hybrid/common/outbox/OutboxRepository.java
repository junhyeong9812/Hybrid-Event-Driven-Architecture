package com.hybrid.common.outbox;

import java.util.List;

// Spring Data JPA의 핵심 인터페이스 — findById, save, delete 등 자동 제공.
import org.springframework.data.jpa.repository.JpaRepository;
// 네이티브 SQL 쿼리 정의용.
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Outbox 영속 추상.
 *
 * 메서드 분류:
 *   1. 파생 쿼리 (findTop100ByStatus..., countByStatus, findByStatus)
 *      - 메서드 이름에서 Spring Data가 SQL 자동 생성.
 *      - 부분 인덱스(idx_outbox_pending) 덕분에 빠름.
 *   2. 네이티브 쿼리 (lockPending)
 *      - PostgreSQL 고유 SKIP LOCKED 사용 — 멀티 인스턴스 Relay 안전성.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // Phase 2 단일 인스턴스 Relay용 — 단순 정렬 fetch.
    // 메서드 이름이 SQL을 결정: Top100 (LIMIT 100) + ByStatus (WHERE) + OrderByCreatedAtAsc (ORDER BY).
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    // Sweeper/통계용 — 특정 상태의 모든 행. 작은 결과 집합 가정.
    List<OutboxEvent> findByStatus(OutboxStatus status);

    // 메트릭(OutboxMetrics)용 — Gauge가 매번 호출하는 카운트.
    // PENDING의 경우 부분 인덱스 덕분에 거의 즉시 결과.
    long countByStatus(OutboxStatus status);

    /**
     * Phase 3 멀티 인스턴스 Relay용 — 행 단위 잠금.
     *
     * FOR UPDATE: 트랜잭션 종료 시까지 그 행을 다른 트랜잭션이 읽거나 수정 못 하게.
     * SKIP LOCKED: 다른 트랜잭션이 이미 잠근 행은 건너뛰고 다음 행을 잡음.
     *
     * → 두 노드가 동시에 폴링해도 한 행은 한 노드만 처리.
     * → @Transactional 종료(커밋/롤백) 시점에 잠금 자동 해제.
     */
    @Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("batchSize") int batchSize);
}
