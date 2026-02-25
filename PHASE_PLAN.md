# 페이즈별 구현 계획

> Hybrid Event-Driven Architecture  
> Java 25 · Spring Boot 4.0 · PostgreSQL 17 · Kafka 3.9  
> 2026년 2월 25일

---

## Phase 1: 모듈러 모놀리스 + 인메모리 이벤트 버스

> **목표:** Order ↔ Payment 도메인 간 인메모리 이벤트 기반 통신 구현  
> **예상 기간:** 2주

### 1.1 프로젝트 초기 설정

- [ ] Gradle 멀티모듈 프로젝트 구성 (`common`, `order`, `payment`, `notification`)
- [ ] Spring Boot 4.0 + Java 25 기본 설정
- [ ] Docker Compose로 PostgreSQL 17 구성
- [ ] 공통 설정 (`application.yml`, 데이터소스, JPA)

```
hybrid-event-driven/
├── common/          # 이벤트 버스, 공통 엔티티
├── order/           # 주문 도메인
├── payment/         # 결제 도메인
├── notification/    # (Phase 2에서 구현)
└── app/             # 메인 애플리케이션 (모듈 조립)
```

### 1.2 인메모리 이벤트 스토어 구현

WAL에서 영감을 받은 append-only 이벤트 저장소를 구현한다.

- [ ] `DomainEvent` 인터페이스 정의 (eventId, eventType, timestamp, aggregateId)
- [ ] `EventStore` 구현 (ConcurrentLinkedQueue 기반)
  - `append(event)` — 이벤트 순차 기록
  - `read(offset, count)` — offset 기반 읽기
  - `getLatestOffset()` — 최신 위치 반환
- [ ] `InMemoryEventBus` 구현
  - `publish(event)` — EventStore에 기록 + 구독자 알림
  - `subscribe(eventType, handler)` — 이벤트 핸들러 등록
- [ ] `EventDispatcher` 구현 — 이벤트 타입별 핸들러 라우팅
- [ ] 트랜잭션 동기화 — `TransactionSynchronization`을 활용하여 커밋 후 이벤트 디스패치, 롤백 시 이벤트 폐기

```java
// 핵심 동작 원리
@Transactional
public void createOrder(CreateOrderCommand cmd) {
    Order order = Order.create(cmd);
    orderRepository.save(order);
    
    // 트랜잭션 커밋 후에 이벤트 디스패치
    eventBus.publish(new OrderCreated(order.getId(), order.getAmount()));
}
```

### 1.3 Order 도메인 구현

- [ ] `Order` 엔티티 (id, customerId, amount, status, createdAt)
- [ ] `OrderStatus` enum (CREATED, PAYMENT_PENDING, CONFIRMED, CANCELLED)
- [ ] `OrderRepository` (Spring Data JPA)
- [ ] `OrderService` — 주문 생성, 상태 변경
- [ ] `OrderCreated`, `OrderConfirmed` 이벤트 정의
- [ ] `OrderEventHandler` — PaymentCompleted 이벤트 수신 시 주문 확정
- [ ] REST API (`POST /api/orders`, `GET /api/orders/{id}`)

### 1.4 Payment 도메인 구현

- [ ] `Payment` 엔티티 (id, orderId, amount, status, createdAt)
- [ ] `PaymentStatus` enum (PENDING, COMPLETED, FAILED, REFUNDED)
- [ ] `PaymentRepository` (Spring Data JPA)
- [ ] `PaymentService` — 결제 처리, 환불
- [ ] `PaymentRequested`, `PaymentCompleted` 이벤트 정의
- [ ] `PaymentEventHandler` — OrderCreated 이벤트 수신 시 결제 처리
- [ ] REST API (`GET /api/payments/{orderId}`)

### 1.5 통합 테스트

- [ ] 이벤트 스토어 단위 테스트 (append, read, offset)
- [ ] 주문 생성 → 결제 처리 → 주문 확정 E2E 테스트
- [ ] 트랜잭션 롤백 시 이벤트 폐기 확인 테스트
- [ ] 동시성 테스트 (여러 주문 동시 생성)

### Phase 1 완료 기준

```
✅ Order 생성 시 OrderCreated 이벤트가 인메모리 스토어에 기록된다
✅ Payment가 OrderCreated를 수신하여 자동으로 결제를 처리한다
✅ PaymentCompleted 수신 시 Order 상태가 CONFIRMED로 변경된다
✅ 트랜잭션 롤백 시 이벤트가 발행되지 않는다
✅ 전체 흐름이 같은 DB 트랜잭션 경계 안에서 동작한다
```

---

## Phase 2: Kafka + Outbox/Inbox 패턴

> **목표:** Order → Notification 도메인 간 Kafka 기반 비동기 통신 + 멱등성 보장  
> **예상 기간:** 2주  
> **선행 조건:** Phase 1 완료

### 2.1 인프라 추가

- [ ] Docker Compose에 Kafka + Zookeeper(또는 KRaft) 추가
- [ ] Kafka 토픽 생성 (`order-events`)
- [ ] Spring Kafka 설정 (`KafkaConfig`, producer/consumer 설정)

```yaml
# docker-compose.yml 추가
kafka:
  image: confluentinc/cp-kafka:7.7
  ports:
    - "9092:9092"
  environment:
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_NODE_ID: 1
    # KRaft 모드 설정...
```

### 2.2 Outbox 패턴 구현 (Order → Kafka)

주문이 확정되면 Outbox 테이블에 이벤트를 기록하고, Relay 프로세스가 Kafka로 발행한다.

- [ ] `outbox` 테이블 생성 (DDL/Flyway 마이그레이션)
- [ ] `OutboxEvent` 엔티티 (id, aggregateType, aggregateId, eventType, payload, status, createdAt, publishedAt)
- [ ] `OutboxRepository` (Spring Data JPA)
- [ ] `OutboxRelay` 구현 — `@Scheduled` 폴링 방식
  - PENDING 상태 이벤트 조회
  - Kafka로 발행
  - 발행 성공 시 PUBLISHED로 상태 변경
  - 발행 실패 시 재시도 (최대 횟수 제한)
- [ ] `OrderService` 수정 — 주문 확정 시 Outbox 테이블에 INSERT (같은 트랜잭션)

```java
@Transactional
public void confirmOrder(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.confirm();
    orderRepository.save(order);
    
    // 같은 트랜잭션에서 Outbox에 기록
    outboxRepository.save(OutboxEvent.of(
        "Order", order.getId().toString(),
        "OrderConfirmed", toJson(order)
    ));
}
```

```sql
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) DEFAULT 'PENDING',
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    published_at    TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox(status) WHERE status = 'PENDING';
```

### 2.3 Inbox 패턴 구현 (Kafka → Notification)

Kafka에서 수신한 메시지를 Inbox 테이블로 멱등성을 체크하고 처리한다.

- [ ] `inbox` 테이블 생성
- [ ] `InboxEvent` 엔티티 (id, messageId, eventType, payload, status, receivedAt)
- [ ] `InboxRepository` (Spring Data JPA)
- [ ] `InboxConsumer` 구현
  - message_id 중복 체크
  - 중복이면 SKIP, 새 메시지면 Inbox INSERT + 비즈니스 로직 처리 (같은 트랜잭션)
- [ ] `NotificationKafkaListener` — Kafka 컨슈머 설정 + InboxConsumer 호출

```java
@Transactional
public void consume(String messageId, String eventType, String payload) {
    // 멱등성 체크
    if (inboxRepository.existsByMessageId(messageId)) {
        log.info("Duplicate message skipped: {}", messageId);
        return;
    }
    
    // Inbox 기록 + 비즈니스 로직 (같은 트랜잭션)
    inboxRepository.save(InboxEvent.of(messageId, eventType, payload));
    notificationService.process(eventType, payload);
}
```

```sql
CREATE TABLE inbox (
    id          BIGSERIAL PRIMARY KEY,
    message_id  VARCHAR(200) UNIQUE NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    payload     JSONB NOT NULL,
    status      VARCHAR(20) DEFAULT 'PROCESSED',
    received_at TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_inbox_message_id ON inbox(message_id);
```

### 2.4 Notification 도메인 구현

- [ ] `Notification` 엔티티 (id, orderId, type, channel, status, sentAt)
- [ ] `NotificationService` — 알림 생성 및 발송 처리
- [ ] `EmailSender` — 이메일 발송 (Mock 구현)
- [ ] `PushSender` — 푸시 알림 발송 (Mock 구현)
- [ ] REST API (`GET /api/notifications/{orderId}`)

### 2.5 통합 테스트

- [ ] Testcontainers로 Kafka 통합 테스트 환경 구성
- [ ] 주문 확정 → Outbox 기록 → Kafka 발행 → Inbox 수신 → 알림 발송 E2E 테스트
- [ ] 중복 메시지 수신 시 Inbox에서 걸러지는지 확인
- [ ] Outbox Relay 실패 시 재시도 동작 확인
- [ ] Kafka 장애 시 Outbox에 메시지가 쌓이고 복구 후 발행되는지 확인

### Phase 2 완료 기준

```
✅ Order 확정 시 Outbox 테이블에 이벤트가 기록된다
✅ Relay가 Outbox를 폴링하여 Kafka로 발행한다
✅ Notification이 Kafka 메시지를 수신하여 알림을 발송한다
✅ 동일 메시지가 2번 이상 도착해도 Inbox에서 중복을 걸러낸다
✅ Kafka 장애 → 복구 후 미발행 메시지가 정상 발행된다
```

---

## Phase 3: 고도화 및 마이크로서비스 전환 준비

> **목표:** 운영 안정성 확보 + 서비스 분리 기반 마련  
> **예상 기간:** 2주  
> **선행 조건:** Phase 2 완료

### 3.1 Outbox Relay 고도화

- [ ] 폴링 방식 → CDC(Change Data Capture) 전환 검토
  - Debezium Embedded Engine 또는 Debezium Server 도입
  - PostgreSQL WAL을 직접 읽어 Kafka로 발행
- [ ] Dead Letter Queue 구현 — 최대 재시도 초과 이벤트 별도 관리
- [ ] Outbox 테이블 클린업 — 발행 완료된 오래된 레코드 주기적 삭제

### 3.2 모니터링 및 관측성

- [ ] Outbox 미발행 건수 메트릭 (Micrometer + Prometheus)
- [ ] Inbox 처리 건수 / 중복 건수 메트릭
- [ ] 이벤트 버스 처리 지연 메트릭
- [ ] Kafka consumer lag 모니터링
- [ ] Grafana 대시보드 구성

```
[주요 메트릭]
outbox.pending.count        — 미발행 이벤트 수 (0에 가까워야 정상)
outbox.publish.success      — 발행 성공 카운터
outbox.publish.failure      — 발행 실패 카운터
inbox.processed.count       — 처리된 메시지 수
inbox.duplicate.count       — 중복으로 스킵된 수
eventbus.dispatch.latency   — 인메모리 이벤트 처리 지연
```

### 3.3 장애 대응 전략

- [ ] 인메모리 이벤트 버스 장애 복구
  - JVM 크래시 시 DB 상태 기반 복구 로직
  - 보상 트랜잭션(Saga) 패턴 검토 및 구현
- [ ] Outbox Relay 장애 시 자동 재시작
- [ ] Kafka 파티션 리밸런싱 시 중복 처리 확인
- [ ] Circuit Breaker 적용 (외부 알림 서비스 연동)

### 3.4 Notification 서비스 분리 준비

- [ ] Notification 모듈의 의존성 분석 — 다른 모듈과의 직접 참조 제거
- [ ] 공유 DB 테이블 분리 계획 수립
- [ ] 독립 실행 가능한 Spring Boot 애플리케이션으로 패키징 테스트
- [ ] Kafka 통신만으로 동작하는지 검증

```
[분리 전]
┌──────────────────────────────────┐
│  Monolith                        │
│  Order ↔ Payment ← Notification  │
│  (공유 DB)                        │
└──────────────────────────────────┘

[분리 후]
┌────────────────────┐     Kafka     ┌──────────────────┐
│  Monolith          │ ───────────► │  Notification    │
│  Order ↔ Payment   │              │  Service         │
│  (DB-A)            │              │  (DB-B)          │
└────────────────────┘              └──────────────────┘
```

### 3.5 Order ↔ Payment 분리 로드맵 (문서화)

Phase 3에서는 실제 분리를 하지 않지만, 전환 계획을 문서화한다.

- [ ] 인메모리 이벤트 버스 → Kafka + Outbox/Inbox 전환 계획
- [ ] 이벤트 인터페이스 호환성 확인 (이벤트 스키마가 동일하므로 전환 비용 최소)
- [ ] 데이터베이스 분리 전략 (공유 테이블 식별, 마이그레이션 계획)
- [ ] 단계적 전환 시나리오 작성

### Phase 3 완료 기준

```
✅ Outbox 미발행 건수, Inbox 처리 현황 등 핵심 메트릭이 대시보드에 노출된다
✅ JVM 크래시 후 재시작 시 미완료 주문이 복구된다
✅ Notification 모듈이 독립적으로 실행 가능하다
✅ Order ↔ Payment 분리 로드맵 문서가 작성되어 있다
✅ Dead Letter Queue로 실패 이벤트가 관리된다
```

---

## 전체 일정 요약

```
Week 1-2    Phase 1: 모듈러 모놀리스 + 인메모리 이벤트 버스
Week 3-4    Phase 2: Kafka + Outbox/Inbox 패턴
Week 5-6    Phase 3: 고도화 + 마이크로서비스 전환 준비
```

| Phase | 핵심 산출물 |
|-------|-----------|
| **1** | 인메모리 이벤트 스토어, Order/Payment 도메인, E2E 테스트 |
| **2** | Outbox/Inbox 테이블, Relay, Kafka 통합, Notification 도메인 |
| **3** | 모니터링 대시보드, 장애 복구 로직, 서비스 분리 검증, 전환 로드맵 |
