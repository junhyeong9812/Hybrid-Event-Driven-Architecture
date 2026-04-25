package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class InboxMigrationTest extends KafkaIntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void inbox_테이블이_message_id에_UNIQUE를_가진다() {
        Integer unique = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'inbox' AND indexname = 'idx_inbox_message_id'
        """, Integer.class);
        assertThat(unique).isEqualTo(1);
    }
}