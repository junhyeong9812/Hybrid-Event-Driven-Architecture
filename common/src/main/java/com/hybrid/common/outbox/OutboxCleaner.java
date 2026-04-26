package com.hybrid.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final JdbcTemplate jdbc;

    public OutboxCleaner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void run() {
        int deleted = jdbc.update("""
            DELETE FROM outbox
            WHERE status = 'PUBLISHED'
              AND published_at < NOW() - INTERVAL '7 days'
        """);
        log.info("outbox cleanup: deleted {} rows", deleted);
    }
}