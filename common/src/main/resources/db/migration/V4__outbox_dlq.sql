-- outbox_dlq 테이블
CREATE TABLE outbox_dlq (
                            id                  BIGSERIAL PRIMARY KEY,
                            original_outbox_id  BIGINT NOT NULL,
                            aggregate_type      VARCHAR(100) NOT NULL,
                            aggregate_id        VARCHAR(100) NOT NULL,
                            event_type          VARCHAR(100) NOT NULL,
                            payload             JSONB NOT NULL,
                            failure_reason      TEXT,
                            moved_at            TIMESTAMP NOT NULL DEFAULT NOW()
);