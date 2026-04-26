package com.hybrid.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 직접 SQL 실행을 위한 JdbcTemplate — JPA로는 대량 DELETE가 비효율적.
import org.springframework.jdbc.core.JdbcTemplate;
// 크론 표현식 스케줄.
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 완료된 오래된 outbox 행을 일괄 삭제하는 백그라운드 잡.
 *
 * 동작:
 *   매일 새벽 3시에 PUBLISHED + published_at이 7일 전인 행을 DELETE.
 *   누적된 outbox 행이 인덱스 효율을 떨어뜨리는 것을 막음.
 *
 * 왜 JPA가 아닌 JdbcTemplate인가:
 *   - 대상 행이 수만~수십만 가능. JPA findAll().deleteAll()은 영속성 컨텍스트에 다 올라가서 OOM 위험.
 *   - 단일 SQL DELETE 한 번이 가장 효율적.
 *   - PostgreSQL의 INTERVAL 표현을 그대로 쓸 수 있음.
 */
@Component
public class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    // 직접 SQL 실행. Spring Boot가 자동 구성한 DataSource로부터 주입.
    private final JdbcTemplate jdbc;

    public OutboxCleaner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 매일 새벽 3시 실행 — 트래픽이 적은 시간대.
     * 크론 형식: 초 분 시 일 월 요일.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void run() {
        // PostgreSQL의 INTERVAL '7 days' — 시간 산술 표현.
        // 부분 인덱스가 PENDING에만 걸려 있고 PUBLISHED는 일반 스캔이지만,
        // 한 번에 처리하므로 새벽 시간엔 부담 없음.
        int deleted = jdbc.update("""
            DELETE FROM outbox
            WHERE status = 'PUBLISHED'
              AND published_at < NOW() - INTERVAL '7 days'
        """);
        // 운영 가시화 — 메트릭 알람의 보조 정보.
        log.info("outbox cleanup: deleted {} rows", deleted);
    }
}
