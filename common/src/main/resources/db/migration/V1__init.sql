-- Phase 1: orders, payments 테이블
-- Order 도메인과 Payment 도메인의 영속 모델.
-- 두 도메인은 서로 직접 SQL 참조하지 않는다 (이벤트로만 통신) — 같은 DB지만 외래키는 두지 않는다.

CREATE TABLE orders (
    id           BIGSERIAL    PRIMARY KEY,
    customer_id  BIGINT       NOT NULL,
    amount       NUMERIC(19,2) NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_status_created
    ON orders(status, created_at);

CREATE TABLE payments (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    amount      NUMERIC(19,2) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order_id
    ON payments(order_id);

CREATE INDEX idx_payments_order_id_status
    ON payments(order_id, status);
