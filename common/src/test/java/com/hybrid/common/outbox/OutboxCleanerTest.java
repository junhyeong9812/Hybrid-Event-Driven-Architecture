package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCleanerTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxCleaner cleaner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 발행된지_7일_이상된_outbox_레코드는_삭제된다() {
        OutboxEvent old = outboxRepo.save(OutboxEvent.of("Order","1","t","{}"));
        old.markPublished();
        jdbc.update("UPDATE outbox SET published_at = NOW() - INTERVAL '8 days' WHERE id = ?",
                old.getId());

        OutboxEvent recent = outboxRepo.save(OutboxEvent.of("Order","2","t","{}"));
        recent.markPublished();

        cleaner.run();

        assertThat(outboxRepo.findById(old.getId())).isEmpty();
        assertThat(outboxRepo.findById(recent.getId())).isPresent();
    }
}