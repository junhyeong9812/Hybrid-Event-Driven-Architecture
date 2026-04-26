# Phase 2 — Kafka + Outbox / Inbox 패턴 (TDD)

> **목표:** Order → Notification 간 Kafka 기반 비동기 통신. at-least-once + 멱등성 처리로 effectively-exactly-once 달성.
> **전제:** Phase 1의 모든 테스트가 green. 인메모리 이벤트 버스는 내부(Order ↔ Payment)에만 사용.
> **완료 기준:** `PHASE_PLAN.md` Phase 2 완료 기준 5항목을 자동화된 테스트로 증명.

---

## 0. 사전 준비

### 0.1 인프라 추가

```yaml
# docker-compose.yml (추가)
  kafka:
    image: confluentinc/cp-kafka:7.7.0
    ports: ["9092:9092"]
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
```

### 0.2 의존성

```groovy
// order/build.gradle, notification/build.gradle
dependencies {
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // Spring Boot 4.0 — 모듈별 테스트 스타터
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testImplementation 'org.testcontainers:kafka:1.20.4'
}
```

### 0.3 테스트 베이스 — Kafka Testcontainers

모든 Phase 2 통합 테스트가 공유하는 베이스.
**testFixtures 소스셋**에 둔다 — 다른 모듈(`order`, `payment`, `notification`, `app`)의 테스트도 상속해 사용한다.

```java
// common/src/testFixtures/java/com/hybrid/common/support/KafkaIntegrationTestBase.java
// PostgreSQL + Kafka Testcontainer를 한 번에 기동해주는 공통 베이스 — Phase 2/3 통합 테스트가 모두 상속한다.
// @Import(KafkaTestSupportConfig.class)로 KafkaProducerStub / KafkaTestConsumer를 자동 등록한다.
package com.hybrid.common.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@Import(KafkaTestSupportConfig.class)
public abstract class KafkaIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

### 0.4 Kafka 테스트 헬퍼 — `KafkaProducerStub`, `KafkaTestConsumer`, `KafkaTestProducer`

Phase 2 테스트들은 세 개의 헬퍼 빈을 자주 쓴다. **베이스 클래스가 자동으로 등록**해주므로 테스트는 그냥 `@Autowired`만 하면 된다.

#### 0.4.0 testFixtures 의존성 — 공용 API 타입은 `testFixturesApi`로

테스트 헬퍼가 `KafkaTemplate`, `KafkaConsumer`, `ProducerFactory` 같은 외부 라이브러리 타입을 자기 공용 API(생성자 매개변수, 상속 부모, 메서드 시그니처)에 노출한다. 이 타입들은 **반드시 `testFixturesApi`** 로 선언해야 한다 — `implementation`에만 두면 testFixtures 소스셋의 컴파일이 main의 transitive 의존에 우연히 기대게 되어 빌드가 부서지기 쉽다.

```groovy
// common/build.gradle
plugins { id 'java-test-fixtures' }

dependencies {
    // 운영 의존성 (생략)
    implementation 'org.springframework.kafka:spring-kafka'
    // ...

    // testFixtures의 공용 API에 등장하는 타입은 명시적으로 노출
    testFixturesApi 'org.springframework.boot:spring-boot-starter-test'   // @SpringBootTest
    testFixturesApi 'org.springframework.kafka:spring-kafka'              // KafkaTemplate, ProducerFactory
    testFixturesApi 'org.apache.kafka:kafka-clients'                      // KafkaConsumer, ProducerRecord, StringDeserializer
    testFixturesApi 'org.testcontainers:junit-jupiter:1.20.4'             // @Testcontainers, @Container
    testFixturesApi 'org.testcontainers:postgresql:1.20.4'                // PostgreSQLContainer
    testFixturesApi 'org.testcontainers:kafka:1.20.4'                     // KafkaContainer
}
```

이렇게 하면 `testImplementation testFixtures(project(':common'))`로 가져간 다른 모듈도 별도 선언 없이 `KafkaTemplate`, `KafkaConsumer` 등을 자유롭게 import할 수 있다.

| 헬퍼 | 역할 |
|------|------|
| `KafkaProducerStub` | 운영 `KafkaTemplate`을 대체. 평소엔 진짜 Kafka 컨테이너로 송신, 테스트가 `failNext` / `alwaysFail` 호출 시 실패 시뮬레이션 |
| `KafkaTestConsumer` | 테스트가 직접 토픽을 구독해 발행 결과를 검증. `@KafkaListener`를 거치지 않고 raw consumer로 |
| `KafkaTestProducer` | 테스트가 직접 토픽으로 메시지를 발행 (외부에서 들어오는 입력 시뮬레이션). `NotificationKafkaListener`가 진짜 메시지를 받았을 때 동작을 검증할 때 쓴다 |

#### 0.4.1 `KafkaProducerStub`

```java
// common/src/testFixtures/java/com/hybrid/common/support/KafkaProducerStub.java
// 운영 KafkaTemplate을 상속해 send()를 가로챈다. failNext/alwaysFail로 실패 주입 가능.
package com.hybrid.common.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

public class KafkaProducerStub extends KafkaTemplate<String, String> {

    private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();
    private final AtomicBoolean alwaysFail = new AtomicBoolean(false);

    public KafkaProducerStub(ProducerFactory<String, String> producerFactory) {
        super(producerFactory);
    }

    /** 다음 send()만 주어진 예외로 실패시킨다. 사용 후 자동 해제. */
    public void failNext(Throwable t) { nextFailure.set(t); }

    /** 이후 모든 send()가 실패한다. reset() 호출 전까지. */
    public void alwaysFail() { alwaysFail.set(true); }

    /** 테스트 사이 정리. */
    public void reset() {
        nextFailure.set(null);
        alwaysFail.set(false);
    }

    @Override
    public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
        if (alwaysFail.get()) {
            return CompletableFuture.failedFuture(
                new RuntimeException("kafka producer stub: alwaysFail"));
        }
        Throwable next = nextFailure.getAndSet(null);
        if (next != null) {
            return CompletableFuture.failedFuture(next);
        }
        return super.send(record);   // 실패 설정 없으면 진짜 Kafka로 발행
    }
}
```

#### 0.4.2 `KafkaTestConsumer`

```java
// common/src/testFixtures/java/com/hybrid/common/support/KafkaTestConsumer.java
// 테스트가 raw Kafka Consumer로 토픽을 구독해 메시지를 확인한다. 토픽별 consumer 캐시.
package com.hybrid.common.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.DisposableBean;

public class KafkaTestConsumer implements DisposableBean {

    private final String bootstrapServers;
    private final Map<String, KafkaConsumer<String, String>> consumers = new ConcurrentHashMap<>();

    public KafkaTestConsumer(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** 한 건만 받아온다. 타임아웃이면 null. */
    public ConsumerRecord<String, String> poll(String topic, Duration timeout) {
        KafkaConsumer<String, String> consumer = getOrCreate(topic);
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        if (records.isEmpty()) return null;
        return records.iterator().next();
    }

    /** 시간 끝까지 도착하는 모든 메시지를 끌어모은다. */
    public List<ConsumerRecord<String, String>> drain(String topic, Duration timeout) {
        KafkaConsumer<String, String> consumer = getOrCreate(topic);
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
            records.forEach(all::add);
        }
        return all;
    }

    public void reset() {
        consumers.values().forEach(KafkaConsumer::close);
        consumers.clear();
    }

    @Override public void destroy() { reset(); }

    private KafkaConsumer<String, String> getOrCreate(String topic) {
        return consumers.computeIfAbsent(topic, this::create);
    }

    private KafkaConsumer<String, String> create(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // 매번 다른 group.id — 테스트끼리 offset이 안 섞이게.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
```

#### 0.4.3 `KafkaTestProducer`

운영 `OutboxRelay` 경로를 거치지 않고, **외부에서 들어오는 메시지를 시뮬레이션**할 때 쓴다. 예: `NotificationKafkaListener`가 진짜 Kafka 메시지를 받았을 때 InboxConsumer 경로가 정확히 동작하는지 검증.

```java
// common/src/testFixtures/java/com/hybrid/common/support/KafkaTestProducer.java
// 테스트가 raw KafkaProducer로 토픽에 메시지를 발행한다. send()는 동기로 ack까지 기다린다.
package com.hybrid.common.support;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;

public class KafkaTestProducer implements DisposableBean {

    private final KafkaProducer<String, String> producer;

    public KafkaTestProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");   // 강한 ack — 데이터 도착 확정 후 반환
        this.producer = new KafkaProducer<>(props);
    }

    public void send(String topic, String key, String value) {
        send(topic, key, value, Map.of());
    }

    public void send(String topic, String key, String value, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((k, v) ->
            record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(record).get();   // ack까지 동기 대기 (테스트 결정성)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while sending", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("send failed for topic " + topic, e.getCause());
        }
    }

    @Override
    public void destroy() { producer.close(); }
}
```

#### 0.4.4 `KafkaTestSupportConfig` — 베이스에서 자동 등록

```java
// common/src/testFixtures/java/com/hybrid/common/support/KafkaTestSupportConfig.java
// KafkaProducerStub, KafkaTestConsumer, KafkaTestProducer를 빈으로 등록.
// KafkaIntegrationTestBase가 @Import로 끌어들인다.
package com.hybrid.common.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.ProducerFactory;

@TestConfiguration
public class KafkaTestSupportConfig {

    /**
     * KafkaProducerStub을 KafkaTemplate 자리에 등록.
     * Spring Boot의 KafkaAutoConfiguration은 @ConditionalOnMissingBean(KafkaTemplate.class)라
     * 우리가 직접 KafkaTemplate(의 하위 타입)을 등록하면 자동 설정이 비활성화된다.
     * @Primary로 다른 정의가 있어도 우리 것이 우선되도록 보강.
     */
    @Bean
    @Primary
    public KafkaProducerStub kafkaProducerStub(ProducerFactory<String, String> producerFactory) {
        return new KafkaProducerStub(producerFactory);
    }

    @Bean
    public KafkaTestConsumer kafkaTestConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaTestConsumer(bootstrapServers);
    }

    @Bean
    public KafkaTestProducer kafkaTestProducer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaTestProducer(bootstrapServers);
    }
}
```

이제 어떤 통합 테스트도 다음과 같이 세 헬퍼를 바로 사용할 수 있다:

```java
class SomeKafkaTest extends KafkaIntegrationTestBase {

    @Autowired KafkaTestConsumer consumer;        // 토픽 구독해 메시지 검증
    @Autowired KafkaProducerStub producerStub;    // 운영 측 발행 실패 시나리오 주입
    @Autowired KafkaTestProducer testProducer;    // 외부 메시지 입력 시뮬레이션
    ...
}
```

#### 0.4.5 세 헬퍼의 사용처 차이 — 한눈에

| 시나리오 | 어떤 헬퍼를 쓰나 |
|---------|---------------|
| outbox → relay → Kafka로 정상 발행, 토픽에 도착했는지 확인 | `KafkaTestConsumer.poll/drain` (수신 측에서 검증) |
| relay가 발행 실패 시 retry_count 증가 검증 | `KafkaProducerStub.failNext` (운영 producer를 가짜 실패로) |
| Kafka에 외부 메시지가 들어왔을 때 NotificationKafkaListener 동작 검증 | `KafkaTestProducer.send` (테스트가 직접 발행) |
| 중복 메시지가 inbox에서 걸러지는지 | `KafkaTestProducer.send`로 같은 messageId 두 번 |

운영 producer 흐름 검증엔 `KafkaProducerStub`, 외부 입력 시뮬레이션엔 `KafkaTestProducer`, 발행 결과 확인엔 `KafkaTestConsumer` — 역할이 깔끔히 갈린다.

---

## Step 1. Outbox 테이블과 엔티티

### 1.1 Red — 마이그레이션이 적용되면 outbox 테이블이 존재한다

```java
// common/src/test/java/com/hybrid/common/outbox/OutboxMigrationTest.java
// Flyway 마이그레이션이 outbox 스키마와 PENDING 부분 인덱스를 정확히 만들었는지 information_schema로 점검한다.
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
```

### 1.2 Green

```sql
-- common/src/main/resources/db/migration/V2__outbox.sql
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
```

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxEvent.java
// outbox 테이블의 JPA 매핑 — 비즈니스 트랜잭션과 함께 저장되는 발행 대기 메시지 한 건을 표현한다.
package com.hybrid.common.outbox;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON) private String payload;
    @Enumerated(EnumType.STRING) private OutboxStatus status = OutboxStatus.PENDING;
    private int retryCount;
    private Instant createdAt = Instant.now();
    private Instant publishedAt;

    public static OutboxEvent of(String type, String aggId, String eventType, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = type; e.aggregateId = aggId;
        e.eventType = eventType; e.payload = payload;
        return e;
    }

    public void markPublished() { this.status = OutboxStatus.PUBLISHED; this.publishedAt = Instant.now(); }
    public void markDeadLetter() { this.status = OutboxStatus.DEAD_LETTER; }
    public void incrementRetry() { this.retryCount++; }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxStatus.java
// 발행 라이프사이클 상태값 — PENDING(대기) / PUBLISHED(발행 완료) / DEAD_LETTER(재시도 초과).
package com.hybrid.common.outbox;

public enum OutboxStatus {
    PENDING, PUBLISHED, DEAD_LETTER
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRepository.java
// outbox 조회/저장 추상. 상태별 카운트와 폴링 대상 조회 메서드를 제공한다.
package com.hybrid.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
    long countByStatus(OutboxStatus status);
}
```

---

## Step 2. 트랜잭션 내 Outbox 기록

### 2.1 Red — `confirmOrder`가 주문 상태 변경과 Outbox 저장을 같은 트랜잭션에서 한다

```java
// order/src/test/java/com/hybrid/order/service/OrderConfirmOutboxTest.java
// 주문 확정 시 도메인 변경과 outbox INSERT가 동일 트랜잭션에서 일어나는지(롤백 시 함께 폐기) 검증한다.
// payment 모듈이 classpath에 없으므로, Phase 1의 자연스러운 체인 대신 orderService.confirm을 직접 호출한다.
package com.hybrid.order.service;

import java.math.BigDecimal;
import java.util.List;

import com.hybrid.common.outbox.OutboxEvent;
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmOutboxTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OutboxRepository outboxRepository;

    @Test
    void 주문_확정_시_outbox에_OrderConfirmed_이벤트가_저장된다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
        orderService.confirm(orderId);

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent e = events.get(0);
        assertThat(e.getAggregateType()).isEqualTo("Order");
        assertThat(e.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(e.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void 주문_확정_트랜잭션_롤백_시_outbox에도_기록되지_않는다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
        long before = outboxRepository.count();

        assertThatThrownBy(() -> orderService.confirmWithForcedRollback(orderId))
                .isInstanceOf(RuntimeException.class);

        assertThat(outboxRepository.count()).isEqualTo(before);
    }
}
```

### 2.2 Green

`OrderService.confirm`이 Outbox에 쓰도록 수정. **Phase 1의 테스트를 깨면 안 된다.**

```java
// order/src/main/java/com/hybrid/order/service/OrderService.java (변경분)
// confirm 메서드를 확장 — 비즈니스 변경에 더해 outbox 행 INSERT까지 같은 @Transactional 안에서 수행한다.
// JsonProcessingException은 try/catch로 잡아 IllegalStateException으로 감싼다 (호출자 API가 JSON 라이브러리에 종속되지 않게).
package com.hybrid.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.OrderConfirmed;
import com.hybrid.common.event.contract.OrderConfirmedPayload;
import com.hybrid.common.outbox.OutboxEvent;
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository repo;
    private final OutboxRepository outboxRepository;
    private final TransactionalEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository repo,
                        OutboxRepository outboxRepository,
                        TransactionalEventPublisher publisher,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void confirm(Long orderId) {
        Order o = repo.findById(orderId).orElseThrow();
        o.confirm();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                new OrderConfirmedPayload(orderId, o.getAmount()));
        } catch (JsonProcessingException e) {
            // payload 클래스 정의 오류 — 프로그래머 오류라 unchecked로 전파.
            // RuntimeException이라 @Transactional 자동 롤백 → outbox INSERT도 폐기.
            throw new IllegalStateException(
                "OrderConfirmed payload 직렬화 실패: orderId=" + orderId, e);
        }

        outboxRepository.save(OutboxEvent.of(
            "Order", orderId.toString(), "OrderConfirmed", payload));

        publisher.publish(new OrderConfirmed(orderId));   // 인메모리 버스는 유지
    }
}
```

**왜 `throws JsonProcessingException`이 아니라 wrap하는가**

| 측면 | `throws` 방식 | wrap as RuntimeException |
|------|------------|----------------------|
| 호출자 API | 모든 호출자가 try/catch 또는 throws 강제 | 깔끔 |
| 도메인 의미 | 도메인 시그니처가 JSON 라이브러리 노출 | 도메인 시그니처 유지 |
| Spring 트랜잭션 | 체크 예외엔 기본 롤백 안 됨 (`rollbackFor` 별도 필요) | RuntimeException은 자동 롤백 |
| 예외의 본질 | 거의 항상 프로그래머 오류(payload 클래스 정의) | unchecked가 정직 |
| fail-fast | 동일 | 동일 |

`JsonProcessingException`은 사용자 입력 오류가 아니라 **payload 클래스 정의 자체가 잘못됐을 때**만 발생한다. 런타임에 잡아도 사용자가 할 수 있는 게 없으니 unchecked로 전파해 트랜잭션을 롤백시키는 게 정직.

### 2.3 Refactor — `OutboxWriter` 헬퍼 추출

같은 try/catch + 직렬화 패턴이 PaymentService, NotificationService에서도 반복될 것이다. 한 번 더 같은 패턴이 등장하면 헬퍼로 뽑는다 (Rule of Three).

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxWriter.java
// outbox 행 기록의 단일 진입점. JSON 직렬화 책임을 도메인 서비스에서 분리한다.
package com.hybrid.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private final OutboxRepository repo;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "outbox payload 직렬화 실패: type=" + eventType + ", id=" + aggregateId, e);
        }
        repo.save(OutboxEvent.of(aggregateType, aggregateId, eventType, json));
    }
}
```

```java
// OrderService.confirm 가 깔끔해진다
@Transactional
public void confirm(Long orderId) {
    Order o = repo.findById(orderId).orElseThrow();
    o.confirm();

    outboxWriter.write("Order", orderId.toString(), "OrderConfirmed",
        new OrderConfirmedPayload(orderId, o.getAmount()));

    publisher.publish(new OrderConfirmed(orderId));
}
```

이후 PaymentService, NotificationService도 같은 `outboxWriter.write(...)`로 사용 가능.

- `@Transactional` 내부에서 예외 발생 시 outbox 저장도 롤백되는지는 2.1의 두 번째 테스트가 이미 증명.
- `OutboxWriter`는 `@Component`라 자체로 트랜잭션 경계를 만들지 않는다 — 호출 측의 `@Transactional` 안에서 동작.

---

## Step 3. OutboxRelay — 폴링 기반 Kafka 발행

### 3.1 Red

```java
// common/src/test/java/com/hybrid/common/outbox/OutboxRelayTest.java
// 폴링→Kafka 발행 성공 경로, 실패 시 retry_count 증가, 멱등성(중복 발행 방지)을 검증한다.
package com.hybrid.common.outbox;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestConsumer;
import com.hybrid.common.support.KafkaProducerStub;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxRelay relay;
    @Autowired KafkaTestConsumer consumer;   // 테스트 전용 컨슈머 빈
    @Autowired KafkaProducerStub kafkaProducerStub;

    @Test
    void PENDING_상태_이벤트를_Kafka로_발행하고_PUBLISHED로_변경한다() {
        OutboxEvent saved = outboxRepo.save(OutboxEvent.of(
            "Order", "42", "OrderConfirmed", "{\"orderId\":42}"));

        relay.poll();   // 스케줄러 직접 호출

        ConsumerRecord<String,String> msg = consumer.poll("order-events", Duration.ofSeconds(5));
        assertThat(msg).isNotNull();
        assertThat(msg.key()).isEqualTo("42");
        assertThat(msg.value()).contains("OrderConfirmed");

        OutboxEvent reloaded = outboxRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void 발행_실패_시_retry_count가_증가한다() {
        OutboxEvent saved = outboxRepo.save(OutboxEvent.of(
            "Order", "42", "OrderConfirmed", "{\"orderId\":42}"));

        kafkaProducerStub.failNext(new TimeoutException("broker down"));
        relay.poll();

        OutboxEvent reloaded = outboxRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
    }

    @Test
    void 여러_번_poll해도_같은_메시지를_두_번_발행하지_않는다() {
        outboxRepo.save(OutboxEvent.of("Order","42","OrderConfirmed","{}"));

        relay.poll();
        relay.poll();
        relay.poll();

        List<ConsumerRecord<String,String>> all = consumer.drain("order-events", Duration.ofSeconds(2));
        assertThat(all).hasSize(1);
    }
}
```

### 3.2 Green

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRelay.java
// @Scheduled 폴링으로 PENDING 행을 읽어 Kafka로 발행하고 결과를 상태에 반영하는 릴레이 컴포넌트.
package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TOPIC = "order-events";
    private static final int BATCH = 100;

    private final OutboxRepository repo;
    private final KafkaTemplate<String,String> kafka;

    public OutboxRelay(OutboxRepository repo, KafkaTemplate<String,String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending =
            repo.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent e : pending) {
            try {
                ProducerRecord<String,String> record = new ProducerRecord<>(
                    TOPIC, e.getAggregateId(), e.getPayload());
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                record.headers().add("eventType", e.getEventType().getBytes());

                kafka.send(record).get(5, TimeUnit.SECONDS);
                e.markPublished();
            } catch (Exception ex) {
                e.incrementRetry();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
    }
}
```

### 3.3 Refactor

- 한 건 실패가 배치 전체를 막지 않도록 `try/catch` 개별화는 이미 적용됨.
- `@Scheduled(fixedDelay=1000)`의 주기는 설정 프로퍼티로 추출: `outbox.relay.poll-interval-ms`.
- `findTop100ByStatusOrderByCreatedAtAsc` → `FOR UPDATE SKIP LOCKED` 쿼리 도입 검토. **멀티 인스턴스에서 중복 발행 방지를 위해 필요.** 지금은 단일 JVM이라 일단 보류, Step 6의 실패 시나리오 테스트에서 다시 판단.

---

## Step 4. Inbox 테이블과 멱등성

### 4.1 Red — 마이그레이션

```java
// notification/src/test/java/com/hybrid/notification/inbox/InboxMigrationTest.java
// inbox 테이블의 message_id UNIQUE 인덱스가 실제 DB에 존재하는지 점검한다.
package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class InboxMigrationTest extends KafkaIntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void inbox_테이블이_message_id에_UNIQUE를_가진다() {
        Integer unique = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'inbox' AND indexname = 'idx_inbox_message_id'
        """, Integer.class);
        assertThat(unique).isEqualTo(1);
    }
}
```

### 4.2 Green

```sql
-- notification/src/main/resources/db/migration/V3__inbox.sql
CREATE TABLE inbox (
    id          BIGSERIAL PRIMARY KEY,
    message_id  VARCHAR(200) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    payload     JSONB NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PROCESSED',
    received_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_inbox_message_id ON inbox(message_id);
```

### 4.3 Red — InboxConsumer의 멱등성

```java
// notification/src/test/java/com/hybrid/notification/inbox/InboxConsumerTest.java
// 동일 messageId 중복 처리 방지, 정상 처리 경로, 실패 시 inbox 함께 롤백을 검증한다.
package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.notification.service.NotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class InboxConsumerTest extends KafkaIntegrationTestBase {

    @Autowired InboxConsumer inboxConsumer;
    @Autowired InboxRepository inboxRepository;
    @MockitoBean NotificationService notificationService;

    @Test
    void 같은_messageId가_두_번_들어오면_두_번째는_스킵된다() {
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{\"orderId\":1}");
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{\"orderId\":1}");

        assertThat(inboxRepository.count()).isEqualTo(1);
        verify(notificationService, times(1)).process(any(), any());
    }

    @Test
    void 서로_다른_messageId는_각각_처리된다() {
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{}");
        inboxConsumer.consume("msg-2", "OrderConfirmed", "{}");

        assertThat(inboxRepository.count()).isEqualTo(2);
        verify(notificationService, times(2)).process(any(), any());
    }

    @Test
    void 비즈니스_로직_예외_시_inbox에도_기록되지_않는다() {
        doThrow(new RuntimeException("boom")).when(notificationService).process(any(), any());

        assertThatThrownBy(() ->
            inboxConsumer.consume("msg-1", "OrderConfirmed", "{}"))
            .isInstanceOf(RuntimeException.class);

        assertThat(inboxRepository.existsByMessageId("msg-1")).isFalse();
    }
}
```

### 4.4 Green

```java
// notification/src/main/java/com/hybrid/notification/inbox/InboxConsumer.java
// 메시지 ID 기반 멱등성 처리 — 중복은 스킵, 신규는 inbox INSERT와 비즈니스 로직을 같은 트랜잭션으로 묶는다.
package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    private final InboxRepository inbox;
    private final NotificationService notifications;

    public InboxConsumer(InboxRepository inbox, NotificationService notifications) {
        this.inbox = inbox;
        this.notifications = notifications;
    }

    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        if (inbox.existsByMessageId(messageId)) {
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        inbox.save(InboxEvent.of(messageId, eventType, payload));
        notifications.process(eventType, payload);
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/inbox/InboxEvent.java
// 수신·처리한 메시지의 ID와 페이로드를 보관 — 중복 수신 판별의 기준이 된다.
package com.hybrid.notification.inbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbox")
public class InboxEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", unique = true, nullable = false)
    private String messageId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private String status = "PROCESSED";
    private Instant receivedAt = Instant.now();

    protected InboxEvent() {}

    public static InboxEvent of(String messageId, String eventType, String payload) {
        InboxEvent e = new InboxEvent();
        e.messageId = messageId;
        e.eventType = eventType;
        e.payload = payload;
        return e;
    }

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
}
```

```java
// notification/src/main/java/com/hybrid/notification/inbox/InboxRepository.java
// Inbox 영속 추상. 멱등성 판별의 핵심 쿼리(existsByMessageId)를 제공한다.
package com.hybrid.notification.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByMessageId(String messageId);
}
```

```java
// notification/src/main/java/com/hybrid/notification/domain/Notification.java
// 발송된 알림의 영속 모델. 채널/타입/시점을 함께 기록한다.
package com.hybrid.notification.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    public enum Channel { EMAIL, PUSH, SMS }
    public enum Status { PENDING, SENT, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private String type;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant sentAt;

    protected Notification() {}

    public static Notification create(Long orderId, String type, Channel channel) {
        Notification n = new Notification();
        n.orderId = orderId;
        n.type = type;
        n.channel = channel;
        n.status = Status.PENDING;
        return n;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed() { this.status = Status.FAILED; }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getType() { return type; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
}
```

```java
// notification/src/main/java/com/hybrid/notification/domain/NotificationRepository.java
// 발송된 알림의 조회/검색 추상.
package com.hybrid.notification.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByOrderId(Long orderId);
    List<Notification> findAllByOrderId(Long orderId);
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/NotificationService.java
// 인박스 컨슈머가 호출하는 알림 처리 유스케이스. 채널별 송신기를 조립해 실제 발송한다.
package com.hybrid.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hybrid.notification.domain.Notification;
import com.hybrid.notification.domain.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final EmailSender emailSender;
    private final PushSender pushSender;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repo,
                               EmailSender emailSender,
                               PushSender pushSender,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.emailSender = emailSender;
        this.pushSender = pushSender;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(String eventType, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            Long orderId = node.get("orderId").asLong();

            Notification email = repo.save(Notification.create(orderId, eventType, Notification.Channel.EMAIL));
            emailSender.send("user-" + orderId + "@example.com", "Order " + orderId + " " + eventType);
            email.markSent();

            Notification push = repo.save(Notification.create(orderId, eventType, Notification.Channel.PUSH));
            pushSender.send(orderId, eventType);
            push.markSent();
        } catch (Exception e) {
            log.error("notification process failed: {}", payload, e);
            throw new IllegalStateException("notification failed", e);
        }
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/EmailSender.java
// 이메일 송신기. Phase 2에서는 Mock 구현, Phase 3에서 Circuit Breaker를 두른다.
package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    public void send(String to, String content) {
        log.info("[email] to={} content={}", to, content);
        // Phase 2: 실제 발송 호출 자리. Mock 구현으로 단순 로깅.
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/PushSender.java
// 푸시 송신기. Phase 2에서는 Mock 구현.
package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    public void send(Long orderId, String eventType) {
        log.info("[push] orderId={} eventType={}", orderId, eventType);
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/web/NotificationController.java
// 발송된 알림 조회 엔드포인트.
package com.hybrid.notification.web;

import java.util.List;

import com.hybrid.notification.domain.Notification;
import com.hybrid.notification.domain.NotificationRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    @GetMapping("/{orderId}")
    public List<Notification> get(@PathVariable Long orderId) {
        return repo.findAllByOrderId(orderId);
    }
}
```

**포인트:** `@Transactional` 덕분에 비즈니스 로직이 실패하면 `inbox.save`도 롤백 → 다음 재수신 때 다시 처리 가능. 4.3의 세 번째 테스트가 이를 증명.

### 4.5 Refactor

- `existsByMessageId`와 `save`가 별도 쿼리여서 **경쟁 조건**이 있다: 동시에 같은 메시지가 들어오면 둘 다 통과 후 `save`에서 UNIQUE 위반. 이는 정상 동작(DB 제약이 최후 방어선)이지만, 예외를 받아서 "중복 스킵"으로 번역해야 한다. → 다음 사이클의 테스트로 추가.

```java
// notification/src/test/java/com/hybrid/notification/inbox/InboxConcurrentDuplicateTest.java
// 동시 두 스레드가 같은 메시지를 처리할 때 UNIQUE 위반이 정확히 한 건만 살아남게 처리되는지 검증한다.
package com.hybrid.notification.inbox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InboxConcurrentDuplicateTest extends KafkaIntegrationTestBase {

    @Autowired InboxConsumer inboxConsumer;
    @Autowired InboxRepository inboxRepository;

    @Test
    void 동시_중복_수신_시_UNIQUE_위반을_중복_스킵으로_처리한다() throws Exception {
        Runnable task = () -> inboxConsumer.consume("msg-1", "t", "{}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        f1.get(); f2.get();

        assertThat(inboxRepository.count()).isEqualTo(1);
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/inbox/InboxConsumer.java (변경분)
// 경쟁 상태 보강 — DataIntegrityViolationException을 잡아 중복 메시지로 번역한다(DB UNIQUE가 최후 방어선).
package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    private final InboxRepository inbox;
    private final NotificationService notifications;

    public InboxConsumer(InboxRepository inbox, NotificationService notifications) {
        this.inbox = inbox;
        this.notifications = notifications;
    }

    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        if (inbox.existsByMessageId(messageId)) {
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        try {
            inbox.save(InboxEvent.of(messageId, eventType, payload));
            notifications.process(eventType, payload);
        } catch (DataIntegrityViolationException dup) {
            log.info("concurrent duplicate skipped: {}", messageId);
        }
    }
}
```

---

## Step 5. Kafka Listener — NotificationKafkaListener

### 5.1 Red

```java
// notification/src/test/java/com/hybrid/notification/kafka/NotificationKafkaListenerTest.java
// 실제 Kafka로 메시지를 보냈을 때 리스너→InboxConsumer→알림 저장까지 흐름이 동작하는지 확인한다.
package com.hybrid.notification.kafka;

import java.util.Map;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.notification.inbox.InboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationKafkaListenerTest extends KafkaIntegrationTestBase {

    @Autowired KafkaTestProducer producer;
    @Autowired InboxRepository inboxRepository;
    @Autowired NotificationRepository notificationRepository;

    @Test
    void Kafka_메시지를_수신하면_InboxConsumer가_호출된다() {
        producer.send("order-events", "42",
            "{\"orderId\":42,\"amount\":1000}",
            Map.of("messageId", "outbox-1", "eventType", "OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(inboxRepository.existsByMessageId("outbox-1")).isTrue();
            assertThat(notificationRepository.findByOrderId(42L)).isPresent();
        });
    }
}
```

### 5.2 Green

```java
// notification/src/main/java/com/hybrid/notification/kafka/NotificationKafkaListener.java
// @KafkaListener 컨슈머. 메시지 헤더(messageId/eventType)를 추출해 InboxConsumer에 위임한다.
package com.hybrid.notification.kafka;

import com.hybrid.notification.inbox.InboxConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationKafkaListener {

    private final InboxConsumer inboxConsumer;

    public NotificationKafkaListener(InboxConsumer inboxConsumer) {
        this.inboxConsumer = inboxConsumer;
    }

    @KafkaListener(topics = "order-events", groupId = "notification")
    public void onMessage(ConsumerRecord<String,String> record) {
        String messageId = header(record, "messageId");
        String eventType = header(record, "eventType");
        inboxConsumer.consume(messageId, eventType, record.value());
    }

    private String header(ConsumerRecord<?,?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }
}
```

---

## Step 6. 실패 시나리오 검증

### 6.1 Red — Kafka 장애 시나리오

**모듈 경계 / 결정성 / 가짜 헬퍼 세 가지를 동시에 풀어야 한다**:

- common 안의 테스트는 **order 모듈을 import할 수 없음** → outbox 행을 직접 INSERT.
- 실제 `KafkaContainer.stop()` / `start()`는 **bootstrap URL이 바뀔 가능성** + producer 재설정 보일러플레이트 → 비결정적. → **`KafkaProducerStub.alwaysFail()` / `reset()`** 으로 결정적 실패 시뮬레이션.
- `awaitConfirmed`, `updateKafkaBootstrapServers` 같은 가짜 헬퍼는 쓰지 않는다 — 모든 흐름은 명시적 호출로.

```java
// common/src/test/java/com/hybrid/common/outbox/KafkaFailureRecoveryTest.java
// Kafka 발행이 실패하는 동안 outbox 상태가 보존되고, 복구 후 자동으로 발행이 따라잡는 시나리오를 검증한다.
// KafkaProducerStub의 alwaysFail/reset으로 결정적 실패 시뮬레이션 — 컨테이너 재시작 비결정성 회피.
package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaFailureRecoveryTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() {
        kafkaProducerStub.reset();   // 다른 테스트로 stub 상태가 새지 않게
    }

    @Test
    void Kafka_발행이_실패하는_동안_outbox에_쌓이고_복구_후_정상_발행된다() {
        // [Arrange] outbox에 PENDING 행 5개 미리 쌓아둔다.
        for (int i = 0; i < 5; i++) {
            outboxRepository.save(OutboxEvent.of(
                "Order", String.valueOf(i), "OrderConfirmed", "{}"));
        }

        // [Act 1] Kafka 발행이 항상 실패하는 상태에서 polling.
        kafkaProducerStub.alwaysFail();
        relay.poll();

        // [Assert 1] 모두 PENDING으로 남아 있어야 한다 (retryCount만 증가).
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING))
                .isEqualTo(5);

        // [Act 2] 발행 정상화 (Kafka 복구 시뮬레이션).
        kafkaProducerStub.reset();

        // [Assert 2] Relay가 polling을 계속하면 결국 PENDING이 0이 된다.
        await().atMost(10, SECONDS).untilAsserted(() -> {
            relay.poll();
            assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING))
                    .isZero();
            assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED))
                    .isEqualTo(5);
        });
    }
}
```

**왜 컨테이너 stop/start 대신 stub을 쓰는가**

| 측면 | `kafka.stop()` / `start()` | `KafkaProducerStub.alwaysFail()` / `reset()` |
|------|------------------------|------------------------------------------|
| 결정성 | 컨테이너 부팅 타이밍 의존 | 즉시 효과 |
| Bootstrap URL 변경 | 재기동 시 포트 바뀔 수 있음 → producer 재설정 필요 | 변경 없음 |
| Spring 컨텍스트 캐시 | 영향 가능 (다른 테스트 영향) | 영향 없음 |
| 테스트 시간 | 부팅 ~10초 + | 즉시 |
| 검증 대상 | 실제 컨테이너 장애 | 발행 실패 처리 로직 자체 |

이 테스트의 **본질은 "발행 실패 → 백압 → 복구 → 자동 따라잡기"** 이지 "Kafka 컨테이너의 stop/start 동작 자체"가 아니다. stub이 의도에 더 충실.

> 운영 단위에서 진짜 Kafka 다운/복구를 검증하는 건 Phase 3의 `ChaosScenarioTest`(app 모듈 E2E)에서 다룬다 — 거기는 모듈 경계가 모두 모인 자리라 적합.

### 6.2 Red — Producer 재시도 의미 없이 실패가 누적되는 케이스

```java
// common/src/test/java/com/hybrid/common/outbox/OutboxDeadLetterTest.java
// MAX_RETRY 초과 시 outbox 행 상태가 DEAD_LETTER로 전이됨을 확인한다.
package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDeadLetterTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxRelay relay;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @Test
    void retry_count가_MAX를_넘으면_DLQ_상태로_전환된다() {
        OutboxEvent e = outboxRepo.save(OutboxEvent.of("Order","42","OrderConfirmed","{}"));
        kafkaProducerStub.alwaysFail();

        for (int i = 0; i < 11; i++) relay.poll();

        OutboxEvent reloaded = outboxRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
    }
}
```

### 6.3 Green — DLQ 상태 도입

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxStatus.java (이미 정의됨)
// DEAD_LETTER 상태 추가 — 재시도 한계를 넘은 outbox 행을 정상 흐름과 분리한다.
package com.hybrid.common.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    DEAD_LETTER
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRelay.java (변경분)
// 발행 실패 시 retryCount를 누적하다가 임계치 초과 시 markDeadLetter 호출로 라이프사이클을 종료시킨다.
package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TOPIC = "order-events";
    private static final int BATCH = 100;
    private static final int MAX_RETRY = 10;

    private final OutboxRepository repo;
    private final KafkaTemplate<String,String> kafka;

    public OutboxRelay(OutboxRepository repo, KafkaTemplate<String,String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending =
            repo.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent e : pending) {
            try {
                ProducerRecord<String,String> record = new ProducerRecord<>(
                    TOPIC, e.getAggregateId(), e.getPayload());
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                record.headers().add("eventType", e.getEventType().getBytes());

                kafka.send(record).get(5, TimeUnit.SECONDS);
                e.markPublished();
            } catch (Exception ex) {
                e.incrementRetry();
                if (e.getRetryCount() >= MAX_RETRY) e.markDeadLetter();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
    }
}
```

> 본격적인 Dead Letter Queue(별도 토픽/테이블)는 Phase 3로 넘기고, 지금은 상태만 분리.

---

## Step 7. Phase 2 E2E

```java
// app/src/test/java/com/hybrid/integration/Phase2E2ETest.java
// 주문 생성→Outbox→Kafka→Inbox→알림까지 한 번에 흐르는지, 중복 메시지가 한 번만 처리되는지 함께 검증한다.
package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.Map;

import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.notification.inbox.InboxRepository;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class Phase2E2ETest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired InboxRepository inboxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired KafkaTestProducer producer;

    @Test
    void 주문_확정부터_알림_발송까지_한_번에_흐른다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        await().atMost(15, SECONDS).untilAsserted(() -> {
            // Phase 1 결과
            assertThat(orderRepo.findById(orderId).orElseThrow().status())
                .isEqualTo(OrderStatus.CONFIRMED);

            // Outbox → Kafka → Inbox → Notification
            assertThat(outboxRepo.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
            assertThat(inboxRepo.count()).isEqualTo(1);
            assertThat(notificationRepo.findByOrderId(orderId)).isPresent();
        });
    }

    @Test
    void 동일_Kafka_메시지가_두_번_수신되어도_알림은_한_번만_발송된다() {
        producer.send("order-events", "42",
            "{\"orderId\":42}", Map.of("messageId","dup-1","eventType","OrderConfirmed"));
        producer.send("order-events", "42",
            "{\"orderId\":42}", Map.of("messageId","dup-1","eventType","OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(inboxRepo.count()).isEqualTo(1);
            assertThat(notificationRepo.findAllByOrderId(42L)).hasSize(1);
        });
    }
}
```

---

## Phase 2 완료 체크리스트

| 완료 기준 | 검증 테스트 |
|----------|------------|
| Order 확정 시 Outbox에 기록된다 | `OrderConfirmOutboxTest.주문_확정_시_outbox에_OrderConfirmed_이벤트가_저장된다` |
| Relay가 Outbox를 폴링하여 Kafka로 발행한다 | `OutboxRelayTest.PENDING_상태_이벤트를_Kafka로_발행하고_PUBLISHED로_변경한다` |
| Notification이 Kafka 메시지를 수신하여 알림을 발송한다 | `NotificationKafkaListenerTest.Kafka_메시지를_수신하면_InboxConsumer가_호출된다` |
| 동일 메시지가 2번 이상 도착해도 Inbox에서 중복을 걸러낸다 | `Phase2E2ETest.동일_Kafka_메시지가_두_번_수신되어도_알림은_한_번만_발송된다` |
| Kafka 장애 → 복구 후 미발행 메시지가 정상 발행된다 | `KafkaFailureRecoveryTest.Kafka가_다운된_동안_outbox에_쌓이고_복구_후_정상_발행된다` |

---

## Phase 1 회귀 체크

Phase 2 작업 중 Phase 1의 테스트가 하나라도 깨지면 그 커밋은 롤백한다.

```bash
./gradlew :order:test :payment:test :common:test   # Phase 1 회귀 세트
./gradlew :notification:test                        # Phase 2 새 세트
```

---

## 다음 페이즈로 넘기는 것

- `SKIP LOCKED`를 활용한 멀티 인스턴스 Relay는 Phase 3로.
- 별도 DLQ 토픽, Debezium CDC 전환은 Phase 3로.
- 메트릭(Prometheus), 대시보드는 Phase 3로.
