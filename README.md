# Hybrid Event-Driven Architecture

> 모듈러 모놀리스 기반 하이브리드 이벤트 드리븐 아키텍처 프로젝트

---

## 프로젝트 개요

모듈러 모놀리스 내부에서는 **인메모리 이벤트 버스**(WAL 방식)로 도메인 간 통신을 처리하고, 외부 시스템과의 통신은 **Kafka + Inbox/Outbox 패턴**으로 일관성과 원자성을 보장하는 하이브리드 아키텍처 프로젝트다.

내부는 가볍게, 외부는 견고하게 — 이 원칙을 코드로 구현한다.

---

## 기술 스택

| 구분 | 기술 | 버전 |
|------|------|------|
| Language | Java | 25 |
| Framework | Spring Boot | 4.0 |
| Database | PostgreSQL | 17 |
| Message Broker | Apache Kafka | 3.9 |
| Build Tool | Gradle (Kotlin DSL) | 8.x |
| Containerization | Docker Compose | - |
| Testing | JUnit 5 + Testcontainers | - |

---

## 아키텍처

```
┌──────────────────────────────────────────────────────────────┐
│                   Modular Monolith (단일 JVM)                 │
│                                                              │
│  ┌────────────┐   In-Memory Event Bus   ┌────────────┐      │
│  │   Order    │◄════(WAL 방식)═══════►│  Payment   │      │
│  │   Domain   │                        │  Domain    │      │
│  └─────┬──────┘                        └────────────┘      │
│        │                                                    │
│        │ Outbox                                             │
│        ▼                                                    │
│  ┌──────────┐                                               │
│  │  Outbox   │──► Relay ──► Kafka ──► Inbox ──┐            │
│  │  Table    │                                │            │
│  └──────────┘                                 ▼            │
│                                        ┌────────────┐      │
│                                        │Notification│      │
│                                        │  Domain    │      │
│                                        └────────────┘      │
└──────────────────────────────────────────────────────────────┘
```

---

## 도메인 구성

| 도메인 | 역할 | 통신 방식 |
|--------|------|----------|
| **Order** | 주문 생성, 변경, 취소, 확정 | 인메모리 이벤트 버스 (↔ Payment) |
| **Payment** | 결제 처리, 환불, 정산 | 인메모리 이벤트 버스 (↔ Order) |
| **Notification** | 이메일, 푸시, SMS 알림 발송 | Kafka + Inbox/Outbox (← Order) |

---

## 프로젝트 구조

```
hybrid-event-driven/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
│
├── common/                              # 공통 모듈
│   └── src/main/java/.../common/
│       ├── event/
│       │   ├── DomainEvent.java         # 이벤트 베이스
│       │   ├── EventStore.java          # 인메모리 이벤트 저장소
│       │   ├── InMemoryEventBus.java    # 내부 이벤트 버스
│       │   └── EventDispatcher.java     # 이벤트 라우팅
│       └── outbox/
│           ├── OutboxEvent.java         # Outbox 엔티티
│           ├── OutboxRepository.java
│           └── OutboxRelay.java         # Outbox → Kafka 릴레이
│
├── order/                               # 주문 도메인
│   └── src/main/java/.../order/
│       ├── domain/
│       │   ├── Order.java
│       │   └── OrderStatus.java
│       ├── event/
│       │   ├── OrderCreated.java
│       │   └── OrderConfirmed.java
│       ├── handler/
│       │   └── OrderEventHandler.java
│       └── service/
│           └── OrderService.java
│
├── payment/                             # 결제 도메인
│   └── src/main/java/.../payment/
│       ├── domain/
│       │   └── Payment.java
│       ├── event/
│       │   ├── PaymentRequested.java
│       │   └── PaymentCompleted.java
│       ├── handler/
│       │   └── PaymentEventHandler.java
│       └── service/
│           └── PaymentService.java
│
├── notification/                        # 알림 도메인
│   └── src/main/java/.../notification/
│       ├── inbox/
│       │   ├── InboxEvent.java
│       │   └── InboxConsumer.java
│       ├── service/
│       │   ├── NotificationService.java
│       │   ├── EmailSender.java
│       │   └── PushSender.java
│       └── kafka/
│           └── NotificationKafkaListener.java
│
└── docs/
    ├── 01_learning_notes.md             # 학습 노트
    ├── 02_architecture_design.md        # 설계 문서
    └── 03_phase_plan.md                 # 페이즈별 구현 계획
```

---

## 실행 방법

### 1. 인프라 실행

```bash
docker-compose up -d
```

PostgreSQL(5432)과 Kafka(9092)가 실행된다.

### 2. 애플리케이션 빌드 및 실행

```bash
./gradlew build
./gradlew bootRun
```

### 3. 테스트

```bash
# 전체 테스트
./gradlew test

# 통합 테스트 (Testcontainers)
./gradlew integrationTest
```

---

## 구현 페이즈

이 프로젝트는 3개의 페이즈로 나누어 점진적으로 구현한다. 각 페이즈의 상세 계획은 `docs/03_phase_plan.md`를 참고한다.

| Phase | 목표 | 핵심 구현 |
|-------|------|----------|
| **Phase 1** | 모듈러 모놀리스 + 인메모리 이벤트 버스 | Order ↔ Payment 내부 통신 |
| **Phase 2** | Kafka + Outbox/Inbox 패턴 | Order → Notification 외부 통신 |
| **Phase 3** | 마이크로서비스 전환 준비 | Notification 분리, 모니터링, 장애 대응 |

---

## 문서

| 문서 | 설명 |
|------|------|
| [학습 노트](docs/01_learning_notes.md) | 멀티테넌트, 메시지큐, Inbox/Outbox, WAL 개념 정리 |
| [설계 문서](docs/02_architecture_design.md) | 하이브리드 이벤트 드리븐 아키텍처 상세 설계 |
| [구현 계획](docs/03_phase_plan.md) | 페이즈별 구현 계획 및 체크리스트 |

---

## 라이선스

MIT License
