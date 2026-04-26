# Hybrid Event-Driven Architecture

> **모듈러 모놀리스 + 인메모리 이벤트 버스 + Outbox/Inbox + Kafka**
> 단일 JVM의 효율과 분산 시스템의 견고함을 양립시키는 패턴 카탈로그.

[![Java](https://img.shields.io/badge/Java-25-orange)]() [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-green)]() [![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)]() [![Kafka](https://img.shields.io/badge/Kafka-3.9-black)]()

---

## 한 문장

내부 도메인 간 통신은 **인메모리 이벤트 버스(WAL 영감)** 로 가볍게, 외부 시스템과의 통신은 **Outbox/Inbox + Kafka** 로 견고하게. 모듈 경계와 의존 방향은 빌드 그래프가 강제한다.

---

## 목차

- [핵심 사상](#핵심-사상)
- [아키텍처 한눈에](#아키텍처-한눈에)
- [기술 스택](#기술-스택)
- [모듈 구조](#모듈-구조)
- [실행 방법](#실행-방법)
- [API & 운영 엔드포인트](#api--운영-엔드포인트)
- [관측성](#관측성)
- [개발 진행 페이즈](#개발-진행-페이즈)
- [테스트 전략](#테스트-전략)
- [문서](#문서)
- [라이선스](#라이선스)

---

## 핵심 사상

```
[내부 통신 — 같은 JVM, 같은 트랜잭션]
   Order ↔ Payment
   └─ InMemoryEventBus + EventStore (강한 일관성)

[외부 통신 — 다른 시스템, 다른 트랜잭션]
   Order → Notification
   Order → 미래의 외부 서비스
   └─ Outbox + Kafka + Inbox (effectively-exactly-once)
```

| 결정 | 이유 |
|------|------|
| 모듈러 모놀리스로 시작 | MSA 운영 부담 회피, 코드 경계로 먼저 분리 |
| 이벤트 컨트랙트(`common.event.contract`) 도입 | 도메인 간 직접 의존 금지, MSA 전환 시 그대로 |
| Outbox 패턴 | DB↔메시지 큐 이중 쓰기 함정 차단 (at-least-once) |
| Inbox 패턴 | 중복 수신 시 멱등 처리 (DB UNIQUE가 최후 방어선) |
| testFixtures로 헬퍼 공유 | 테스트 인프라가 운영 jar로 새지 않게 |
| `@CircuitBreaker` (Resilience4j) | 외부 장애 격리 |
| 부분 인덱스 / FOR UPDATE SKIP LOCKED | PostgreSQL 고유 기능으로 운영 효율 ↑ |

---

## 아키텍처 한눈에

```
┌──────────────────────────────────────────────────────────────────────┐
│                  Modular Monolith (단일 JVM)                          │
│                                                                      │
│   ┌──────────┐    InMemoryEventBus    ┌──────────┐                  │
│   │  Order   │◄════ contract events ═►│ Payment  │                  │
│   │ (도메인) │       (강한 일관성)      │ (도메인) │                  │
│   └────┬─────┘                         └──────────┘                  │
│        │                                                             │
│        │ Outbox 테이블 INSERT (같은 트랜잭션)                         │
│        ▼                                                             │
│   ┌─────────────────────────────────────┐                            │
│   │       common (인프라)                │                            │
│   │  ┌──────────────┐  ┌──────────────┐ │                            │
│   │  │ OutboxEvent  │→ │ OutboxRelay  │─┼──► Kafka ──┐               │
│   │  └──────────────┘  └──────────────┘ │           │               │
│   │  EventStore, EventBus,              │           │               │
│   │  Transaction Sync, Metrics, DLQ ... │           │               │
│   └─────────────────────────────────────┘           │               │
│                                                      │               │
│   ┌────────────────────────────────────────────────┐ │               │
│   │         app (조립 + 운영)                       │ │               │
│   │  HybridEventDrivenApplication (모놀리스 main)   │ │               │
│   │  DeadLetterAdminController, OrderRecoveryJob    │ │               │
│   │  Prometheus 노출, E2E 테스트, Chaos             │ │               │
│   └────────────────────────────────────────────────┘ │               │
│                                                      ▼               │
│   ┌────────────────────────────────────────────────────────────────┐ │
│   │  notification (도메인 + Kafka 컨슈머)                           │ │
│   │  ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐ │ │
│   │  │ Kafka        │──► │ InboxConsumer│──► │ NotificationSvc   │ │ │
│   │  │ Listener     │    │ (멱등 처리)   │    │ (Email + Push)    │ │ │
│   │  └──────────────┘    └──────────────┘    └─┬─────────────────┘ │ │
│   │                                            │                   │ │
│   │                                            ▼ @CircuitBreaker   │ │
│   │                                          EmailGateway          │ │
│   │                                            │                   │ │
│   │                                            ▼ (OPEN 시)         │ │
│   │                                          DeferredEmailQueue    │ │
│   └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 기술 스택

| 영역 | 도구 |
|------|------|
| 언어 / JVM | Java 25 |
| 프레임워크 | Spring Boot 4.0, Spring Framework 7 |
| 데이터 액세스 | Spring Data JPA, JdbcTemplate, Hibernate 6 |
| 마이그레이션 | Flyway 11 (+ flyway-database-postgresql) |
| 데이터베이스 | PostgreSQL 17 (JSONB, 부분 인덱스, SKIP LOCKED) |
| 메시징 | Apache Kafka 3.9 (KRaft 모드), Spring Kafka |
| 회복성 | Resilience4j (Circuit Breaker) |
| 관측성 | Micrometer + Prometheus + Spring Boot Actuator |
| 빌드 | Gradle 9.3 (Groovy DSL), java-test-fixtures 플러그인 |
| 테스트 | JUnit 5, AssertJ, Mockito (`@MockitoBean`), Awaitility, Testcontainers, ArchUnit |
| 컨테이너 | Docker Compose (Postgres + Kafka) |

---

## 모듈 구조

```
hybrid-event-driven/
├── settings.gradle                # 멀티모듈 선언
├── build.gradle                   # subprojects 공통 (toolchain 25, 테스트 의존)
├── docker-compose.yml             # PostgreSQL 17 + Kafka 3.9 KRaft
├── .gitignore                     # build/, .idea/, *.log, ... (docs/는 추적)
│
├── common/                         # 횡단 인프라 + 이벤트 컨트랙트
│   ├── src/main/java/com/hybrid/common/
│   │   ├── event/                  # EventBus, EventStore, TransactionalEventPublisher
│   │   │   └── contract/           # 도메인 간 통신용 이벤트 (OrderCreated 등)
│   │   └── outbox/                 # OutboxEvent, OutboxRelay, OutboxWriter,
│   │                                 OutboxMetrics, OutboxCleaner, DLQ
│   ├── src/main/resources/db/migration/
│   │   ├── V1__init.sql            # orders, payments
│   │   ├── V2__outbox.sql          # outbox + 부분 인덱스
│   │   └── V4__outbox_dlq.sql      # outbox_dlq
│   └── src/testFixtures/java/      # KafkaIntegrationTestBase, KafkaProducerStub,
│                                     KafkaTestConsumer, KafkaTestProducer (모듈 공용)
│
├── order/                          # 주문 도메인
│   └── src/main/java/com/hybrid/order/
│       ├── domain/                 # Order, OrderStatus, OrderRepository
│       ├── service/                # OrderService, CreateOrderCommand
│       ├── handler/                # OrderEventHandler (PaymentCompleted 구독)
│       └── web/                    # OrderController (REST API)
│
├── payment/                        # 결제 도메인
│   └── src/main/java/com/hybrid/payment/
│       ├── domain/                 # Payment, PaymentStatus, PaymentRepository
│       ├── service/                # PaymentService
│       ├── handler/                # PaymentEventHandler (OrderCreated 구독)
│       └── web/                    # PaymentController
│
├── notification/                   # 알림 도메인 + 외부 통신 종단
│   ├── src/main/java/com/hybrid/notification/
│   │   ├── NotificationApplication.java   # Phase 3 단독 실행 진입점
│   │   ├── domain/                 # Notification, NotificationRepository
│   │   ├── inbox/                  # InboxEvent, InboxConsumer (멱등 처리)
│   │   ├── kafka/                  # NotificationKafkaListener (@KafkaListener)
│   │   ├── service/                # NotificationService, EmailSender, EmailGateway,
│   │   │                             LoggingEmailGateway, DeferredEmailQueue, PushSender
│   │   └── web/                    # NotificationController
│   └── src/main/resources/db/migration/
│       └── V3__inbox.sql           # inbox + UNIQUE(message_id)
│
└── app/                            # 모놀리스 조립 + 운영 + E2E 테스트
    ├── build.gradle                # bootJar 빌드 + 모든 모듈 + actuator/prometheus
    ├── src/main/java/com/hybrid/
    │   ├── HybridEventDrivenApplication.java  # @SpringBootApplication 진입점
    │   ├── admin/                  # DeadLetterAdminController (운영 HTTP)
    │   └── recovery/               # OrderRecoveryJob (cross-domain orchestration)
    ├── src/main/resources/
    │   └── application.yml         # DataSource, Kafka, Actuator, Resilience4j
    └── src/test/java/com/hybrid/integration/   # Phase 1/2/3 E2E + Chaos
```

### 모듈 의존 그래프

```
       common (인프라 + 컨트랙트)
        ↑
        │ implementation
        │
   ┌────┼─────┬───────────┐
   │    │     │           │
order payment notification ─── 도메인 모듈 (서로 의존 안 함, 컨트랙트로만 통신)
   │    │     │           │
   └────┼─────┴───────────┘
        │
        ▼
       app (모든 모듈 + bootJar + 운영)
```

규칙:
- 도메인 → 인프라 의존 OK
- 인프라 → 도메인 의존 금지
- 도메인 ↔ 도메인 직접 의존 금지 (반드시 contract 경유)
- 운영 / HTTP / E2E는 app만의 책임

---

## 실행 방법

### 1. 인프라 기동

```bash
docker compose up -d
# PostgreSQL: 5432
# Kafka     : 9092 (KRaft 모드)
```

### 2. 빌드 + 실행

```bash
./gradlew :app:bootJar
java -jar app/build/libs/app-0.1.0.jar

# 또는
./gradlew :app:bootRun
```

### 3. Notification 단독 실행 (Phase 3)

```bash
./gradlew :notification:bootRun
# Order/Payment 없이 Kafka 구독만 활성
```

### 4. 테스트 실행

```bash
./gradlew test                        # 전체
./gradlew :common:test                # 인프라 단위
./gradlew :app:test                   # E2E 통합
./gradlew :notification:test          # Inbox/Kafka 통합
```

(Docker가 떠있으면 Testcontainers가 PostgreSQL/Kafka를 자동 기동/정리)

---

## API & 운영 엔드포인트

### 도메인 API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/orders` | 주문 생성 → `OrderCreated` 발행 → Payment 자동 처리 |
| `GET` | `/api/orders/{id}` | 주문 조회 |
| `GET` | `/api/payments/{orderId}` | 주문ID로 결제 상태 조회 |
| `GET` | `/api/notifications/{orderId}` | 발송된 알림 목록 |

### 운영 / Admin

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/admin/dlq/{id}/retry` | DLQ 행을 PENDING outbox로 되돌리기 (수동 재시도) |
| `GET` | `/actuator/health` | 헬스체크 |
| `GET` | `/actuator/prometheus` | Prometheus 메트릭 (스크래핑) |
| `GET` | `/actuator/info` | 빌드 정보 |

---

## 관측성

### 핵심 메트릭

| 이름 | 타입 | 의미 |
|------|------|------|
| `outbox.pending.count` | Gauge | 발행 대기 중인 outbox 행 (0 수렴) |
| `outbox.deadletter.count` | Gauge | DLQ 행 (0이어야 정상, 0 초과 시 알람) |
| `outbox.publish.success` | Counter | 누적 발행 성공 |
| `outbox.publish.failure` | Counter | 누적 발행 실패 (rate가 비정상이면 알람) |
| `inbox.processed.count` | Counter | Inbox 처리 누적 |
| `inbox.duplicate.count` | Counter | 중복 스킵 누적 |
| `outbox.deadletter.moved` | Counter | DLQ 이관 누적 |
| `order.recovered` | Counter | 복구 잡이 confirm시킨 주문 |

JVM, HTTP 응답시간, HikariCP 풀, Kafka 컨슈머 lag 등은 **Spring Boot Actuator + Micrometer가 자동 노출**.

### 추천 알람 조건

```promql
# DLQ가 쌓이면
outbox_deadletter_count > 0

# 발행 실패율 5분 이상 1% 초과
rate(outbox_publish_failure_total[5m])
  / rate(outbox_publish_success_total[5m]) > 0.01

# Pending이 1000건 넘으면 (발행 정체)
outbox_pending_count > 1000
```

---

## 개발 진행 페이즈

| Phase | 목표 | 핵심 산출물 |
|-------|------|-----------|
| **1** | 모듈러 모놀리스 + 인메모리 이벤트 버스 | EventStore, EventBus, TransactionalEventPublisher, Order/Payment 도메인 |
| **2** | Outbox/Inbox + Kafka | OutboxEvent, OutboxRelay, InboxConsumer, NotificationKafkaListener, DLQ 상태 |
| **3** | 운영 안정성 + MSA 전환 준비 | Metrics, OutboxCleaner, DeadLetterSweeper, OrderRecoveryJob, Circuit Breaker, ArchUnit, Notification 단독 실행, Chaos 테스트 |

각 페이즈는 **TDD 사이클(Red→Green→Refactor)** 로 진행. 자세한 단계별 계획은 [`docs/phase-1-tdd.md`](docs/phase-1-tdd.md), [`docs/phase-2-tdd.md`](docs/phase-2-tdd.md), [`docs/phase-3-tdd.md`](docs/phase-3-tdd.md).

---

## 테스트 전략

| 계층 | 도구 | 위치 | 예시 |
|------|------|------|------|
| 단위 | JUnit 5 + AssertJ | 각 도메인 모듈 `src/test` | `OrderTest`, `EventStoreTest` |
| 슬라이스 | `@WebMvcTest` + `@MockitoBean` | 도메인 모듈 | `OrderControllerTest` |
| 통합 (모듈 내) | Testcontainers + `KafkaIntegrationTestBase` | 각 모듈 `src/test` | `OutboxRelayTest`, `InboxConsumerTest` |
| E2E (모듈 간) | 모든 모듈 + Testcontainers | `app/integration/` | `Phase1E2ETest`, `Phase2E2ETest` |
| 모듈 경계 검증 | ArchUnit | `notification/test/standalone/` | `NotificationModuleBoundaryTest` |
| 단독 부팅 검증 | `@SpringBootTest(classes=NotificationApplication.class)` | `notification/test/standalone/` | `NotificationStandaloneTest` |
| Chaos | Stub 기반 결정적 실패 시뮬레이션 | `app/integration/` | `ChaosScenarioTest` |

테스트 헬퍼는 모두 [`common/src/testFixtures/`](common/src/testFixtures/java/com/hybrid/common/support/)에 — 다른 모듈도 `testImplementation testFixtures(project(':common'))`로 사용.

---

## 문서

| 문서 | 내용 |
|------|------|
| [`docs/README.md`](docs/README.md) | 문서 인덱스 + TDD 진행 규약 |
| [`docs/phase-1-tdd.md`](docs/phase-1-tdd.md) | Phase 1 단계별 구현 (Red/Green/Refactor) |
| [`docs/phase-2-tdd.md`](docs/phase-2-tdd.md) | Phase 2 단계별 구현 |
| [`docs/phase-3-tdd.md`](docs/phase-3-tdd.md) | Phase 3 단계별 구현 |
| [`docs/architecture-analysis.md`](docs/architecture-analysis.md) | **완성된 시스템에 대한 종합 분석** (이 README의 학술적 확장) |
| [`docs/study/`](docs/study/) | 19개 학습 노트 (Spring/Kafka/Java/PostgreSQL/Gradle 등) |

학습 노트 색인은 [`docs/study/architecture-overview.md`](docs/study/architecture-overview.md)에 있습니다.

---

## 라이선스

MIT License
