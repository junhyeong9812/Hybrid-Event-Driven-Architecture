package com.hybrid.common.outbox;

import java.util.List;
import java.util.Map;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMigrationTest extends KafkaIntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void outbox_테이블이_존재하고_필수_컬럼을_가진다() {
        List<Map<String,Object>> cols = jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'outbox'
        """);
        assertThat(cols).extracting(c -> c.get("column_name"))
                .contains("id","aggregate_type","aggregate_id",
                        "event_type","payload","status",
                        "retry_count","created_at","published_at");
    }

    @Test
    void 부분_인덱스가_PENDING에만_걸려있다() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'outbox' AND indexname = 'idx_outbox_pending'
        """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}