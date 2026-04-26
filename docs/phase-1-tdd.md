# Phase 1 — 모듈러 모놀리스 + 인메모리 이벤트 버스 (TDD)

> **목표:** Order ↔ Payment 도메인 간 인메모리 이벤트 통신 구현.
> **전제:** 같은 JVM, 같은 DB 트랜잭션 경계. 강한 일관성.
> **완료 기준:** `PHASE_PLAN.md` Phase 1 완료 기준 5항목 전부 자동화된 테스트로 증명.

---

## 0. 사전 준비 (Setup)

TDD 시작 전, "빌드가 돌고 테스트가 실행되는" 최소 환경을 먼저 만든다. 이 단계는 TDD 사이클의 대상이 아니다.

### 0.1 Gradle 멀티모듈 구성

```groovy
// settings.gradle
rootProject.name = 'hybrid-event-driven'
include 'common', 'order', 'payment', 'notification', 'app'
```

```groovy
// build.gradle (root)
plugins {
    id 'org.springframework.boot' version '4.0.0' apply false
    id 'io.spring.dependency-management' version '1.1.6' apply false
}

allprojects {
    group = 'com.hybrid'
    version = '0.1.0'
    repositories { mavenCentral() }
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    java {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    dependencies {
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
        testImplementation 'org.testcontainers:junit-jupiter:1.20.4'
        testImplementation 'org.testcontainers:postgresql:1.20.4'
    }

    tasks.withType(Test).configureEach { useJUnitPlatform() }
}
```

### 0.2 Docker Compose (PostgreSQL만 우선)

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:17
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: hybrid
      POSTGRES_USER: hybrid
      POSTGRES_PASSWORD: hybrid
```

### 0.3 Flyway 마이그레이션 — `orders`, `payments` 테이블

도메인 엔티티(`Order`, `Payment`)와 1:1 매핑되는 DDL을 작성한다. 두 도메인은 같은 DB를 공유하지만 **외래키는 두지 않는다** — 도메인 간 결합을 SQL 레벨에서도 분리하기 위함이다 (관계는 `order_id` 컬럼만으로 표현).

```sql
-- common/src/main/resources/db/migration/V1__init.sql
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
```

**왜 외래키(`FOREIGN KEY (order_id) REFERENCES orders(id)`)를 안 두는가**
- Order ↔ Payment는 **이벤트 기반 비동기 통신**이 정상 흐름이다. SQL 외래키로 묶으면 도메인 분리 의지에 모순.
- Phase 3에서 Notification을 분리할 때, Order ↔ Payment 분리도 같은 길을 갈 수 있어야 한다. 외래키가 있으면 DB 분리가 불가능해진다.
- 정합성은 **애플리케이션 레벨의 이벤트 흐름 + 회복 잡(Phase 3 `OrderRecoveryJob`)** 으로 보장.

**`ddl-auto: validate`와의 관계**

`app/src/main/resources/application.yml`의 `spring.jpa.hibernate.ddl-auto: validate` 설정 때문에, Flyway가 만든 스키마와 JPA 엔티티가 **정확히 일치**해야 컨텍스트가 뜬다. 이 마이그레이션이 비어 있으면 `Schema-validation` 예외로 모든 통합 테스트가 실패한다.

> Phase 2의 V2(outbox), V3(inbox), V4(outbox_dlq) 마이그레이션도 같은 폴더에 순서대로 추가된다.

### 0.4 실행 확인

```bash
./gradlew build     # 모든 모듈 컴파일
./gradlew test      # 0개 테스트라도 SUCCESS 출력 확인
```

여기까지 되면 Red 사이클을 시작할 수 있다.

---

## Step 1. `DomainEvent` 인터페이스

### 1.1 Red — 실패 테스트

모든 이벤트는 `eventId`, `eventType`, `occurredAt`, `aggregateId`를 가진다. 구현체를 만들기 전에 계약을 테스트로 고정한다.

```java
// common/src/test/java/com/hybrid/common/event/DomainEventTest.java
// DomainEvent 인터페이스의 계약(고유 eventId / 생성 시각 / aggregateId)을 고정하는 단위 테스트.
package com.hybrid.common.event;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventTest {

    @Test
    void 이벤트는_고유한_eventId를_가진다() {
        DomainEvent e1 = new TestEvent("agg-1");
        DomainEvent e2 = new TestEvent("agg-1");

        assertThat(e1.eventId()).isNotNull();
        assertThat(e1.eventId()).isNotEqualTo(e2.eventId());
    }

    @Test
    void 이벤트는_생성_시점을_기록한다() {
        Instant before = Instant.now();
        DomainEvent e = new TestEvent("agg-1");
        Instant after = Instant.now();

        assertThat(e.occurredAt()).isBetween(before, after);
    }

    @Test
    void 이벤트는_aggregateId를_노출한다() {
        DomainEvent e = new TestEvent("agg-42");
        assertThat(e.aggregateId()).isEqualTo("agg-42");
    }

    record TestEvent(String aggregateId) implements DomainEvent {
        @Override public String eventType() { return "TestEvent"; }
    }
}
```

이 시점에는 `DomainEvent` 타입이 없으므로 컴파일 실패 → 정상(Red).

### 1.2 Green — 최소 구현

```java
// common/src/main/java/com/hybrid/common/event/DomainEvent.java
// 모든 도메인 이벤트의 최상위 타입. 각 이벤트 인스턴스가 가져야 할 식별자/타임스탬프/집계 키 계약을 정의한다.
package com.hybrid.common.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    default UUID eventId() { return UUID.randomUUID(); }      // 호출 시마다 다름 방지 필요 → Refactor 단계로
    default Instant occurredAt() { return Instant.now(); }
    String eventType();
    String aggregateId();
}
```

### 1.3 Refactor

기본 구현이 호출 때마다 다른 값을 돌려주면 테스트가 불안정해진다. `AbstractDomainEvent`로 고정값을 보관한다.

```java
// common/src/main/java/com/hybrid/common/event/AbstractDomainEvent.java
// eventId/occurredAt 값을 인스턴스 생성 시점에 고정해 테스트 안정성과 동등성 검사를 보장하는 기본 구현.
package com.hybrid.common.event;

import java.time.Instant;
import java.util.UUID;

public abstract class AbstractDomainEvent implements DomainEvent {
    private final UUID eventId = UUID.randomUUID();
    private final Instant occurredAt = Instant.now();

    @Override public UUID eventId() { return eventId; }
    @Override public Instant occurredAt() { return occurredAt; }
}
```

테스트는 `AbstractDomainEvent`를 상속하도록 조정하고 모두 green 유지.

---

## Step 2. `EventStore` — append-only 인메모리 로그

### 2.1 Red

```java
// common/src/test/java/com/hybrid/common/event/EventStoreTest.java
// append-only 이벤트 스토어의 순차성·offset 단조성·동시 append 안정성을 검증한다.
package com.hybrid.common.event;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventStoreTest {

    @Test
    void append한_이벤트는_read로_조회된다() {
        EventStore store = new InMemoryEventStore();
        DomainEvent e = new OrderCreatedTestEvent("order-1");

        long offset = store.append(e);
        List<DomainEvent> read = store.read(offset, 1);

        assertThat(read).containsExactly(e);
    }

    @Test
    void offset은_단조증가한다() {
        EventStore store = new InMemoryEventStore();
        long o1 = store.append(new OrderCreatedTestEvent("o1"));
        long o2 = store.append(new OrderCreatedTestEvent("o2"));
        long o3 = store.append(new OrderCreatedTestEvent("o3"));

        assertThat(o1).isLessThan(o2);
        assertThat(o2).isLessThan(o3);
    }

    @Test
    void read는_지정한_offset부터_count만큼_반환한다() {
        EventStore store = new InMemoryEventStore();
        long first = store.append(new OrderCreatedTestEvent("o1"));
        store.append(new OrderCreatedTestEvent("o2"));
        store.append(new OrderCreatedTestEvent("o3"));

        List<DomainEvent> read = store.read(first + 1, 2);

        assertThat(read).hasSize(2);
        assertThat(read.get(0).aggregateId()).isEqualTo("o2");
        assertThat(read.get(1).aggregateId()).isEqualTo("o3");
    }

    @Test
    void 동시에_append해도_offset이_중복되지_않는다() throws Exception {
        EventStore store = new InMemoryEventStore();
        int threads = 16, perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Long> offsets = ConcurrentHashMap.newKeySet();

        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++)
                    offsets.add(store.append(new OrderCreatedTestEvent("x")));
                done.countDown();
            });
        }
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(offsets).hasSize(threads * perThread);
    }
}
```

### 2.2 Green

```java
// common/src/main/java/com/hybrid/common/event/EventStore.java
// 이벤트의 append-only 저장과 offset 기반 read를 노출하는 추상 — WAL의 인메모리 구현 인터페이스.
package com.hybrid.common.event;

import java.util.List;

public interface EventStore {
    long append(DomainEvent event);
    List<DomainEvent> read(long from, int count);
    long latestOffset();
}
```

```java
// common/src/main/java/com/hybrid/common/event/InMemoryEventStore.java
// CopyOnWriteArrayList + AtomicLong 기반의 인메모리 이벤트 로그 구현체.
package com.hybrid.common.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryEventStore implements EventStore {

    private final List<DomainEvent> log = new CopyOnWriteArrayList<>();
    private final AtomicLong offset = new AtomicLong(0);

    @Override
    public synchronized long append(DomainEvent event) {
        long current = offset.getAndIncrement();
        log.add(event);
        return current;
    }

    @Override
    public List<DomainEvent> read(long from, int count) {
        int end = (int) Math.min(from + count, log.size());
        if (from >= log.size()) return List.of();
        return List.copyOf(log.subList((int) from, end));
    }

    @Override
    public long latestOffset() { return offset.get(); }
}
```

### 2.3 Refactor

- `synchronized` 범위를 좁히고 `append`/`size`의 일관성을 `ReentrantLock`으로 명시.
- `read`가 `List.copyOf`로 매번 방어 복사하는 비용 → 테스트 통과 유지하며 `Collections.unmodifiableList(new ArrayList<>(...))` 비교 또는 프로파일 후 판단(지금은 유지).

---

## Step 3. `InMemoryEventBus` — publish/subscribe

> 참고: 이 테스트가 사용하는 `OrderCreated` / `PaymentCompleted`는 다른 모듈에 있어 common 테스트에서 직접 import할 수 없다. 실제로는 `common` 안의 `TestEvent` 더미 클래스로 대체하거나, 이 통합 검증을 `app` 모듈로 옮긴다. 아래 코드는 의도를 명확히 보여주기 위해 도메인 이벤트를 그대로 사용한다.

### 3.1 Red

```java
// app/src/test/java/com/hybrid/integration/InMemoryEventBusTest.java
// publish/subscribe 동작, 타입별 라우팅, EventStore 연동, 핸들러 예외 격리를 검증한다.
package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hybrid.common.event.DomainEvent;
import com.hybrid.common.event.EventStore;
import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.InMemoryEventStore;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.common.event.contract.PaymentCompleted;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventBusTest {

    @Test
    void subscribe한_핸들러는_publish시_호출된다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        List<DomainEvent> received = new ArrayList<>();
        bus.subscribe(OrderCreated.class, received::add);

        OrderCreated event = new OrderCreated(1L, BigDecimal.valueOf(1000));
        bus.publish(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void 다른_타입_이벤트는_해당_타입_핸들러만_호출한다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        List<DomainEvent> orderHandler = new ArrayList<>();
        List<DomainEvent> paymentHandler = new ArrayList<>();
        bus.subscribe(OrderCreated.class, orderHandler::add);
        bus.subscribe(PaymentCompleted.class, paymentHandler::add);

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(orderHandler).hasSize(1);
        assertThat(paymentHandler).isEmpty();
    }

    @Test
    void publish한_이벤트는_EventStore에도_append된다() {
        EventStore store = new InMemoryEventStore();
        InMemoryEventBus bus = new InMemoryEventBus(store);

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(store.latestOffset()).isEqualTo(1L);
    }

    @Test
    void 한_핸들러의_예외가_다른_핸들러를_중단시키지_않는다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        bus.subscribe(OrderCreated.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(OrderCreated.class, e -> secondCalled.set(true));

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(secondCalled).isTrue();
    }
}
```

### 3.2 Green

```java
// common/src/main/java/com/hybrid/common/event/EventBus.java
// 이벤트 발행과 타입별 구독을 노출하는 추상. 구현체는 인메모리/외부 브로커로 교체 가능하다.
package com.hybrid.common.event;

import java.util.function.Consumer;

public interface EventBus {
    <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler);
    void publish(DomainEvent event);
}
```

```java
// common/src/main/java/com/hybrid/common/event/InMemoryEventBus.java
// 동기식 인메모리 버스. EventStore에 append 후 등록된 핸들러를 순차 호출하며 한 핸들러의 예외가 다른 핸들러를 막지 않는다.
package com.hybrid.common.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryEventBus implements EventBus {

    private final EventStore store;
    private final Map<Class<? extends DomainEvent>, List<Consumer<DomainEvent>>> handlers
            = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    public InMemoryEventBus(EventStore store) { this.store = store; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                .add((Consumer<DomainEvent>) handler);
    }

    @Override
    public void publish(DomainEvent event) {
        store.append(event);
        for (Consumer<DomainEvent> h : handlers.getOrDefault(event.getClass(), List.of())) {
            try { h.accept(event); }
            catch (Exception e) { log.error("handler failed for {}", event, e); }
        }
    }
}
```

### 3.3 Refactor

- 예외 삼키는 정책을 명시적 `EventErrorHandler` 인터페이스로 분리해 테스트 가능하게 만든다.
- 타입 캐스트(`(Consumer<DomainEvent>) handler`)를 `TypedHandler` 래퍼로 감싸 경고 제거.

---

## Step 4. 트랜잭션 동기화 — 커밋 후 디스패치, 롤백 시 폐기

이 단계가 Phase 1의 핵심. "트랜잭션 롤백 시 이벤트가 발행되지 않는다"를 테스트로 증명한다.

### 4.1 Red — Testcontainers 사용

```java
// app/src/test/java/com/hybrid/integration/TransactionalEventPublishingTest.java
// 트랜잭션 커밋 후에만 디스패치, 롤백 시 폐기됨을 PostgreSQL Testcontainer로 증명한다.
package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import com.hybrid.common.event.EventStore;
import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.OrderCreated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class TransactionalEventPublishingTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired TransactionalEventPublisher publisher;
    @Autowired PlatformTransactionManager txm;
    @Autowired EventStore store;

    @Test
    void 트랜잭션_커밋_후에_이벤트가_디스패치된다() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        AtomicLong offsetDuringTx = new AtomicLong(-1);

        tx.executeWithoutResult(status -> {
            publisher.publish(new OrderCreated(1L, BigDecimal.TEN));
            offsetDuringTx.set(store.latestOffset());        // 아직 append 전
        });

        assertThat(offsetDuringTx.get()).isZero();
        assertThat(store.latestOffset()).isEqualTo(1L);       // 커밋 후
    }

    @Test
    void 트랜잭션_롤백_시_이벤트는_폐기된다() {
        TransactionTemplate tx = new TransactionTemplate(txm);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publish(new OrderCreated(1L, BigDecimal.TEN));
            throw new RuntimeException("rollback");
        })).isInstanceOf(RuntimeException.class);

        assertThat(store.latestOffset()).isZero();
    }
}
```

### 4.2 Green

```java
// common/src/main/java/com/hybrid/common/event/TransactionalEventPublisher.java
// TransactionSynchronization을 활용해 커밋 후에만 EventBus.publish가 실행되도록 보장하는 어댑터.
package com.hybrid.common.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TransactionalEventPublisher {

    private final InMemoryEventBus bus;

    public TransactionalEventPublisher(InMemoryEventBus bus) { this.bus = bus; }

    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bus.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { bus.publish(event); }
            }
        );
    }
}
```

### 4.3 Refactor

- `afterCompletion(status)` 훅에서 `STATUS_ROLLED_BACK`일 때 로그로 폐기 기록.
- 여러 이벤트를 모아 배치로 디스패치할 수 있도록 `ThreadLocal<List<DomainEvent>>` 버퍼 도입 여부는 Step 7의 성능 테스트 결과를 보고 결정(지금은 YAGNI).

---

## Step 5. Order 도메인

### 5.1 Red — 도메인 단위 테스트 우선

```java
// order/src/test/java/com/hybrid/order/domain/OrderTest.java
// Order 애그리거트의 불변식(초기 상태, 양수 금액, 상태 전이 제약)을 도메인 단위로 검증한다.
package com.hybrid.order.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void 주문_생성_시_상태는_CREATED다() {
        Order o = Order.create(1L, BigDecimal.valueOf(1000));
        assertThat(o.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 금액은_양수여야_한다() {
        assertThatThrownBy(() -> Order.create(1L, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void CREATED_상태에서만_confirm_가능하다() {
        Order o = Order.create(1L, BigDecimal.TEN);
        o.confirm();
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);

        assertThatThrownBy(o::confirm).isInstanceOf(IllegalStateException.class);
    }
}
```

### 5.2 Green

```java
// order/src/main/java/com/hybrid/order/domain/Order.java
// 주문 애그리거트. JPA 매핑과 함께 생성/확정 등 상태 전이 규칙을 캡슐화한다.
package com.hybrid.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long customerId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private Instant createdAt;

    protected Order() {}

    public static Order create(Long customerId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Order o = new Order();
        o.customerId = customerId;
        o.amount = amount;
        o.status = OrderStatus.CREATED;
        o.createdAt = Instant.now();
        return o;
    }

    public void confirm() {
        if (status != OrderStatus.CREATED)
            throw new IllegalStateException("cannot confirm from " + status);
        status = OrderStatus.CONFIRMED;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public OrderStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// order/src/main/java/com/hybrid/order/domain/OrderStatus.java
// 주문의 라이프사이클 상태값. 상태 전이는 Order 애그리거트 메서드가 강제한다.
package com.hybrid.order.domain;

public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED
}
```

```java
// order/src/main/java/com/hybrid/order/domain/OrderRepository.java
// Spring Data JPA 인터페이스. 도메인 코드에서 SQL/JPQL을 직접 다루지 않게 분리한다.
package com.hybrid.order.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    long countByStatus(OrderStatus status);
    List<Order> findByStatus(OrderStatus status);
}
```

```java
// common/src/main/java/com/hybrid/common/event/contract/OrderCreated.java
// "주문이 생성됐다"는 사실을 나타내는 도메인 이벤트. Payment 도메인의 트리거가 된다.
// 도메인 간 공유 계약이라 common 모듈의 contract 패키지에 둔다 — 어느 도메인에도 종속되지 않게.
package com.hybrid.common.event.contract;

import java.math.BigDecimal;

import com.hybrid.common.event.AbstractDomainEvent;

public class OrderCreated extends AbstractDomainEvent {

    private final Long orderId;
    private final BigDecimal amount;

    public OrderCreated(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public Long orderId() { return orderId; }
    public BigDecimal amount() { return amount; }

    @Override public String eventType() { return "OrderCreated"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
```

```java
// common/src/main/java/com/hybrid/common/event/contract/OrderConfirmed.java
// "주문이 확정됐다"는 사실. Phase 2에서 Outbox에 기록되어 Notification으로 전파된다.
package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

public class OrderConfirmed extends AbstractDomainEvent {

    private final Long orderId;

    public OrderConfirmed(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    @Override public String eventType() { return "OrderConfirmed"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
```

```java
// common/src/main/java/com/hybrid/common/event/contract/OrderConfirmedPayload.java
// Outbox JSON 직렬화 전용 DTO — DomainEvent와 분리해 외부 스키마를 안정적으로 유지한다.
package com.hybrid.common.event.contract;

import java.math.BigDecimal;

public record OrderConfirmedPayload(Long orderId, BigDecimal amount) {}
```

```java
// order/src/main/java/com/hybrid/order/service/CreateOrderCommand.java
// 주문 생성 입력값을 묶은 불변 커맨드. 컨트롤러/테스트가 서비스를 호출할 때 사용.
package com.hybrid.order.service;

import java.math.BigDecimal;

public record CreateOrderCommand(Long customerId, BigDecimal amount) {}
```

### 5.3 Red — 서비스 계층 + 이벤트 발행

```java
// order/src/test/java/com/hybrid/order/service/OrderServiceTest.java
// 주문 생성 시 OrderCreated 이벤트가 EventBus를 통해 정확히 1회 발행되는지 통합 테스트로 확인한다.
package com.hybrid.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OrderServiceTest {

    @Autowired OrderService orderService;
    @Autowired InMemoryEventBus eventBus;

    @Test
    void 주문_생성_시_OrderCreated_이벤트가_발행된다() {
        List<OrderCreated> captured = new ArrayList<>();
        eventBus.subscribe(OrderCreated.class, captured::add);

        Long id = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).orderId()).isEqualTo(id);
    }
}
```

### 5.4 Green

```java
// order/src/main/java/com/hybrid/order/service/OrderService.java
// 주문 생성/확정 유스케이스. 도메인 저장과 이벤트 발행을 같은 트랜잭션 경계 안에서 수행한다.
package com.hybrid.order.service;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.common.event.contract.OrderConfirmed;
import com.hybrid.common.event.contract.OrderCreated;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository repo;
    private final TransactionalEventPublisher publisher;

    public OrderService(OrderRepository repo, TransactionalEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Transactional
    public Long create(CreateOrderCommand cmd) {
        Order order = Order.create(cmd.customerId(), cmd.amount());
        repo.save(order);
        publisher.publish(new OrderCreated(order.getId(), order.getAmount()));
        return order.getId();
    }

    @Transactional
    public void confirm(Long orderId) {
        Order o = repo.findById(orderId).orElseThrow();
        o.confirm();
        publisher.publish(new OrderConfirmed(orderId));
    }
}
```

### 5.5 Red + Green — REST API (WebMvcTest 슬라이스)

```java
// order/src/test/java/com/hybrid/order/web/OrderControllerTest.java
// REST 슬라이스 테스트 — 생성 응답 코드(201)와 Location 헤더 규약을 고정한다.
package com.hybrid.order.web;

import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;

    @Test
    void POST_api_orders는_201과_Location_헤더를_반환한다() throws Exception {
        given(orderService.create(any())).willReturn(42L);

        mockMvc.perform(post("/api/orders")
                .contentType(APPLICATION_JSON)
                .content("""
                    { "customerId": 1, "amount": 1000 }
                """))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/orders/42"));
    }
}
```

```java
// order/src/main/java/com/hybrid/order/web/OrderController.java
// 주문 도메인의 HTTP 진입점. 비즈니스 로직은 Service에 위임하고 HTTP 규약(상태코드/헤더)만 책임진다.
package com.hybrid.order.web;

import java.net.URI;

import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateOrderCommand cmd) {
        Long id = orderService.create(cmd);
        return ResponseEntity.created(URI.create("/api/orders/" + id)).build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable Long id) {
        return orderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

---

## Step 6. Payment 도메인

Order와 동일한 패턴. 핵심 차이는 **OrderCreated를 받아 자동으로 결제를 실행**한다는 점이다.

### 6.1 Red — 핸들러가 이벤트를 받으면 결제가 만들어진다

```java
// payment/src/test/java/com/hybrid/payment/handler/PaymentEventHandlerTest.java
// OrderCreated 수신 시 Payment 생성과 PaymentCompleted 발행이 자동 연쇄되는지 검증한다.
// payment 모듈은 order에 의존하지 않으므로, OrderService를 거치지 않고 eventBus.publish로 OrderCreated를 직접 던져 검증한다.
package com.hybrid.payment.handler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.payment.domain.PaymentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentEventHandlerTest {

    @Autowired InMemoryEventBus eventBus;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void OrderCreated_수신_시_Payment가_생성되고_PaymentCompleted가_발행된다() {
        List<PaymentCompleted> captured = new ArrayList<>();
        eventBus.subscribe(PaymentCompleted.class, captured::add);

        eventBus.publish(new OrderCreated(1L, BigDecimal.valueOf(1000)));

        await().atMost(2, SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).orderId()).isEqualTo(1L);
        });
    }
}
```

### 6.2 Green

```java
// payment/src/main/java/com/hybrid/payment/handler/PaymentEventHandler.java
// OrderCreated 구독자 — 결제 유스케이스 호출의 진입점. 핸들러 실행은 트랜잭션 안에서 이뤄진다.
package com.hybrid.payment.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.payment.service.PaymentService;


import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventHandler {

    private final PaymentService paymentService;

    public PaymentEventHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostConstruct
    void register(@Autowired InMemoryEventBus bus) {
        bus.subscribe(OrderCreated.class, this::onOrderCreated);
    }

    @Transactional
    public void onOrderCreated(OrderCreated event) {
        paymentService.process(event.orderId(), event.amount());
    }
}
```

```java
// payment/src/main/java/com/hybrid/payment/service/PaymentService.java
// 결제 처리 유스케이스. Mock 결제 후 PaymentCompleted를 발행해 Order의 확정 흐름을 트리거한다.
package com.hybrid.payment.service;

import java.math.BigDecimal;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository repo;
    private final TransactionalEventPublisher publisher;

    public PaymentService(PaymentRepository repo, TransactionalEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Transactional
    public void process(Long orderId, BigDecimal amount) {
        Payment p = Payment.request(orderId, amount);
        repo.save(p);
        p.complete();           // Mock 결제: 즉시 성공
        publisher.publish(new PaymentCompleted(orderId));
    }
}
```

```java
// payment/src/main/java/com/hybrid/payment/domain/Payment.java
// 결제 애그리거트. 결제 요청과 완료 같은 상태 전이를 캡슐화한다.
package com.hybrid.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payments")
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private Instant createdAt;

    protected Payment() {}

    public static Payment request(Long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Payment p = new Payment();
        p.orderId = orderId;
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        return p;
    }

    public void complete() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot complete from " + status);
        status = PaymentStatus.COMPLETED;
    }

    public void fail() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot fail from " + status);
        status = PaymentStatus.FAILED;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// payment/src/main/java/com/hybrid/payment/domain/PaymentStatus.java
// 결제의 라이프사이클 상태값.
package com.hybrid.payment.domain;

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}
```

```java
// payment/src/main/java/com/hybrid/payment/domain/PaymentRepository.java
// 결제 영속 추상. 주문ID로의 조회가 자주 일어나므로 파생 쿼리를 노출한다.
package com.hybrid.payment.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
    List<Payment> findAllByOrderId(Long orderId);
}
```

```java
// common/src/main/java/com/hybrid/common/event/contract/PaymentRequested.java
// 결제가 시작됐다는 사실. 운영 추적·감사 용도로 발행한다.
package com.hybrid.common.event.contract;

import java.math.BigDecimal;

import com.hybrid.common.event.AbstractDomainEvent;

public class PaymentRequested extends AbstractDomainEvent {

    private final Long orderId;
    private final BigDecimal amount;

    public PaymentRequested(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public Long orderId() { return orderId; }
    public BigDecimal amount() { return amount; }

    @Override public String eventType() { return "PaymentRequested"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
```

```java
// common/src/main/java/com/hybrid/common/event/contract/PaymentCompleted.java
// 결제 완료 사실. Order 도메인이 이를 받아 주문을 CONFIRMED로 전이한다.
package com.hybrid.common.event.contract;

import com.hybrid.common.event.AbstractDomainEvent;

public class PaymentCompleted extends AbstractDomainEvent {

    private final Long orderId;

    public PaymentCompleted(Long orderId) { this.orderId = orderId; }

    public Long orderId() { return orderId; }

    @Override public String eventType() { return "PaymentCompleted"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
```

```java
// payment/src/main/java/com/hybrid/payment/web/PaymentController.java
// 결제 도메인의 HTTP 진입점. 주문ID로 결제 상태를 조회하는 단일 엔드포인트.
package com.hybrid.payment.web;

import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository repo;

    public PaymentController(PaymentRepository repo) { this.repo = repo; }

    @GetMapping("/{orderId}")
    public ResponseEntity<Payment> getByOrder(@PathVariable Long orderId) {
        return repo.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

### 6.3 Red — Order가 PaymentCompleted를 받아 확정된다

```java
// order/src/test/java/com/hybrid/order/handler/OrderEventHandlerTest.java
// PaymentCompleted 수신 시 주문 상태가 CONFIRMED로 전이되는지 검증한다.
// payment 모듈 의존을 피하려고 PaymentCompleted를 eventBus.publish로 직접 던진다.
package com.hybrid.order.handler;

import java.math.BigDecimal;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderEventHandlerTest {

    @Autowired InMemoryEventBus eventBus;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;

    @Test
    void PaymentCompleted_수신_시_Order_상태가_CONFIRMED로_변경된다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));

        eventBus.publish(new PaymentCompleted(orderId));

        await().atMost(2, SECONDS).untilAsserted(() -> {
            Order o = orderRepository.findById(orderId).orElseThrow();
            assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        });
    }
}
```

### 6.4 Green

```java
// order/src/main/java/com/hybrid/order/handler/OrderEventHandler.java
// PaymentCompleted 구독자. 결제 완료 시 OrderService.confirm을 호출해 주문 확정 단계로 진행시킨다.
package com.hybrid.order.handler;

import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.order.service.OrderService;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class OrderEventHandler {

    private final OrderService orderService;

    public OrderEventHandler(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostConstruct
    void register(InMemoryEventBus bus) {
        bus.subscribe(PaymentCompleted.class, this::onPaymentCompleted);
    }

    public void onPaymentCompleted(PaymentCompleted event) {
        orderService.confirm(event.orderId());
    }
}
```

### 6.5 Refactor

- `@PostConstruct`에서 `bus.subscribe`를 호출하는 패턴이 반복된다 → `@EventHandler` 커스텀 어노테이션 + `ApplicationRunner`로 자동 등록하는 방법을 검토.
- 단, "지금 2개뿐이니 추상화는 3개째가 생길 때" 원칙에 따라 보류.

---

## Step 7. E2E 흐름 검증

Phase 1 완료 기준 5항목을 **하나의 통합 테스트 클래스**로 묶어 증명한다.

```java
// app/src/test/java/com/hybrid/integration/Phase1E2ETest.java
// Phase 1 완료 기준 5항목을 묶어 증명하는 E2E 테스트 — 정상 흐름·롤백·동시성을 동시에 점검한다.
package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class Phase1E2ETest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void 주문_생성부터_확정까지_이벤트_체인이_동작한다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        await().atMost(3, SECONDS).untilAsserted(() -> {
            assertThat(orderRepository.findById(orderId).orElseThrow().status())
                .isEqualTo(OrderStatus.CONFIRMED);
            assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().status())
                .isEqualTo(PaymentStatus.COMPLETED);
        });
    }

    @Test
    void 주문_생성_트랜잭션_롤백_시_Payment는_만들어지지_않는다() {
        assertThatThrownBy(() ->
            orderService.createWithForcedRollback(new CreateOrderCommand(1L, BigDecimal.TEN)))
            .isInstanceOf(RuntimeException.class);

        assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    void 동시에_100개_주문을_생성해도_각각_결제가_완료된다() throws Exception {
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++)
            futures.add(pool.submit(() ->
                orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN))));

        Set<Long> ids = new HashSet<>();
        for (var f : futures) ids.add(f.get());

        await().atMost(10, SECONDS).untilAsserted(() -> {
            long confirmed = orderRepository.countByStatus(OrderStatus.CONFIRMED);
            assertThat(confirmed).isEqualTo(n);
            assertThat(ids).hasSize(n);
        });
    }
}
```

---

## Phase 1 완료 체크리스트

테스트가 **모두 green**이면 Phase 1 완료.

| 완료 기준 | 검증 테스트 |
|----------|------------|
| OrderCreated가 EventStore에 기록된다 | `InMemoryEventBusTest.publish한_이벤트는_EventStore에도_append된다` |
| Payment가 OrderCreated를 수신해 결제한다 | `PaymentEventHandlerTest.OrderCreated_수신_시_Payment가_생성되고_PaymentCompleted가_발행된다` |
| PaymentCompleted 수신 시 Order가 CONFIRMED로 변경된다 | `OrderEventHandlerTest.PaymentCompleted_수신_시_Order_상태가_CONFIRMED로_변경된다` |
| 트랜잭션 롤백 시 이벤트가 발행되지 않는다 | `TransactionalEventPublishingTest.트랜잭션_롤백_시_이벤트는_폐기된다` |
| 전체 흐름이 같은 트랜잭션 경계에서 동작한다 | `Phase1E2ETest.주문_생성_트랜잭션_롤백_시_Payment는_만들어지지_않는다` |

---

## 다음 페이즈로 넘기는 것

- Notification 도메인은 **아직 구현하지 않는다**. Phase 1은 인메모리로만 끝낸다.
- Outbox 테이블도 **아직 만들지 않는다**. Phase 2에서 Order에 추가된다.
- Kafka 의존성은 **빌드 파일에 추가하지 않는다**. Phase 2에서 도입한다.
