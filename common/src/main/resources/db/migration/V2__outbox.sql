-- outbox 테이블 + idx_outbox_pending 부분 인덱스
CREATE TABLE outbox (
                        id              BIGSERIAL PRIMARY KEY,
                        aggregate_type  VARCHAR(100) NOT NULL,
                        aggregate_id    VARCHAR(100) NOT NULL,
                        event_type      VARCHAR(100) NOT NULL,
                        payload         JSONB NOT NULL,
                        status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        retry_count     INT NOT NULL DEFAULT 0,
                        created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                        published_at    TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE status = 'PENDING';