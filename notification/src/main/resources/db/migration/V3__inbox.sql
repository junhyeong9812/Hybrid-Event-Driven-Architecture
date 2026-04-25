-- inbox 테이블 + idx_inbox_message_id UNIQUE 인덱스
CREATE TABLE inbox (
                       id          BIGSERIAL PRIMARY KEY,
                       message_id  VARCHAR(200) NOT NULL,
                       event_type  VARCHAR(100) NOT NULL,
                       payload     JSONB NOT NULL,
                       status      VARCHAR(20) NOT NULL DEFAULT 'PROCESSED',
                       received_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_inbox_message_id ON inbox(message_id);