# TDD 기반 페이즈별 구현 문서

> Hybrid Event-Driven Architecture 프로젝트의 구현 가이드.
> 각 페이즈는 **테스트를 먼저 작성(Red) → 최소 구현으로 통과(Green) → 리팩토링(Refactor)** 사이클로 진행한다.

---

## 문서 구성

| 문서 | 내용 |
|------|------|
| [phase-1-tdd.md](./phase-1-tdd.md) | 모듈러 모놀리스 + 인메모리 이벤트 버스 구축 |
| [phase-2-tdd.md](./phase-2-tdd.md) | Kafka + Outbox / Inbox 패턴 도입 |
| [phase-3-tdd.md](./phase-3-tdd.md) | 모니터링 · 장애 대응 · 마이크로서비스 전환 준비 |
| [study/](./study/) | 학습 노트 — 구현 중 마주친 개념을 정리한 문서들 |

---

## TDD 진행 규약

### 한 사이클의 단위

각 "Step"은 하나의 TDD 사이클이다.

```
1. Red      — 실패하는 테스트 작성 후 실행하여 실패를 확인한다
2. Green    — 테스트가 통과할 최소한의 프로덕션 코드를 작성한다
3. Refactor — 중복 제거, 네이밍 개선, 구조 정리. 테스트는 계속 green 상태여야 한다
```

### 테스트 계층

| 계층 | 도구 | 범위 |
|------|------|------|
| 단위 테스트 | JUnit 5, Mockito | 순수 도메인 / 개별 컴포넌트 |
| 슬라이스 테스트 | `@DataJpaTest`, `@WebMvcTest` | 특정 레이어 |
| 통합 테스트 | Testcontainers (PostgreSQL, Kafka) | 여러 컴포넌트 + 실제 인프라 |
| E2E 테스트 | `@SpringBootTest` + Testcontainers | 도메인 간 이벤트 흐름 전체 |

### 공통 원칙

- **테스트 없이 프로덕션 코드를 쓰지 않는다.** 버그 수정 역시 재현 테스트부터 쓴다.
- **한 번에 하나의 실패만 다룬다.** 여러 테스트가 동시에 빨갛다면 범위가 너무 크다.
- **구현은 테스트가 요구하는 만큼만.** YAGNI. 미래 요구를 상상해 코드를 부풀리지 않는다.
- **Refactor 단계에서 새 기능을 넣지 않는다.** 리팩토링과 기능 추가는 섞지 않는다.
- **통합 테스트는 Testcontainers를 기본으로.** Mock으로 감추면 Phase 2의 분산 문제를 잡아낼 수 없다.

### 커밋 규약

각 사이클 단위로 커밋한다.

```
test(order): add failing test for Order.create validation   # Red
feat(order): implement Order.create                         # Green
refactor(order): extract OrderFactory                       # Refactor
```

---

## 전체 로드맵

```
[Phase 1]                  [Phase 2]                     [Phase 3]
모듈러 모놀리스            Kafka + Outbox/Inbox          운영 안정성 + MSA 전환 준비
In-Memory Event Bus   →    외부 시스템 통신         →    모니터링 · 장애 복구 · 분리
(Order ↔ Payment)          (Order → Notification)        (Notification 독립 실행)

강한 일관성(트랜잭션 내)    최종 일관성(at-least-once)   + Observability / Resilience
```

각 페이즈는 **직전 페이즈의 테스트를 깨뜨리지 않는 것**을 기본 전제로 한다. 회귀 테스트는 모든 페이즈에서 통과되어야 하며, 새 기능의 테스트만 추가로 쌓인다.
