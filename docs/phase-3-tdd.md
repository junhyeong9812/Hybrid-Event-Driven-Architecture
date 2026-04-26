# Phase 3 — 고도화 · 장애 대응 · MSA 전환 준비 (TDD)

> **목표:** 운영 안정성을 테스트로 증명하고, Notification 모듈이 독립 실행 가능한 상태로 만든다.
> **전제:** Phase 1/2의 모든 테스트가 green.
> **완료 기준:** `PHASE_PLAN.md` Phase 3 완료 기준 5항목을 자동화된 테스트와 문서로 증명.

Phase 3은 기능 추가보다는 **관측성 / 복원력 / 분리 가능성**에 초점을 둔다. TDD 사이클이 "새 테스트 → 최소 구현"인 것은 동일하지만, 테스트가 "지표가 노출되는가", "장애가 복구되는가" 같은 **관측 가능한 속성**을 검증한다.

---

## Step 1. Metrics — Micrometer 통합

### 1.1 Red

**테스트는 모듈 경계에 맞게 둘로 분리**한다 — common 메트릭(outbox)과 notification 메트릭(inbox)은 각자 자기 모듈에서 검증. common 테스트가 notification.InboxConsumer를 import하면 모듈 경계 위반.

```java
// common/src/test/java/com/hybrid/common/outbox/OutboxMetricsTest.java
// common 모듈의 outbox 메트릭(pending count, publish success)만 검증.
// inbox 메트릭은 notification/test의 InboxConsumerMetricsTest로 분리.
package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxMetricsTest extends KafkaIntegrationTestBase {

    @Autowired MeterRegistry registry;
    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxRelay relay;

    @Test
    void outbox_pending_count_게이지가_PENDING_레코드_수를_반영한다() {
        outboxRepo.save(OutboxEvent.of("Order","1","OrderConfirmed","{}"));
        outboxRepo.save(OutboxEvent.of("Order","2","OrderConfirmed","{}"));

        Gauge g = registry.find("outbox.pending.count").gauge();
        assertThat(g).isNotNull();
        await().atMost(2, SECONDS).untilAsserted(() ->
            assertThat(g.value()).isEqualTo(2.0));
    }

    @Test
    void relay_발행_성공_카운터가_증가한다() {
        outboxRepo.save(OutboxEvent.of("Order","1","OrderConfirmed","{}"));
        Counter before = registry.counter("outbox.publish.success");
        double v0 = before.count();

        relay.poll();

        assertThat(registry.counter("outbox.publish.success").count())
            .isGreaterThan(v0);
    }
}
```

```java
// notification/src/test/java/com/hybrid/notification/inbox/InboxConsumerMetricsTest.java
// notification 모듈의 inbox 메트릭(duplicate, processed)을 검증.
package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InboxConsumerMetricsTest extends KafkaIntegrationTestBase {

    @Autowired MeterRegistry registry;
    @Autowired InboxConsumer inboxConsumer;

    @Test
    void inbox_duplicate_count_카운터가_중복_수신_시_증가한다() {
        inboxConsumer.consume("msg-1","OrderConfirmed","{\"orderId\":1}");
        inboxConsumer.consume("msg-1","OrderConfirmed","{\"orderId\":1}");

        assertThat(registry.counter("inbox.duplicate.count").count())
            .isEqualTo(1.0);
    }

    @Test
    void inbox_processed_count_카운터가_정상_처리_시_증가한다() {
        double before = registry.counter("inbox.processed.count").count();

        inboxConsumer.consume("msg-unique","OrderConfirmed","{\"orderId\":99}");

        assertThat(registry.counter("inbox.processed.count").count())
            .isGreaterThan(before);
    }
}
```

### 1.2 Green

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxMetrics.java
// outbox.pending.count / outbox.deadletter.count 게이지를 등록하는 운영 관측용 컴포넌트.
package com.hybrid.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repo) {
        Gauge.builder("outbox.pending.count",
                () -> repo.countByStatus(OutboxStatus.PENDING))
             .register(registry);
        Gauge.builder("outbox.deadletter.count",
                () -> repo.countByStatus(OutboxStatus.DEAD_LETTER))
             .register(registry);
    }
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRelay.java (변경분)
// 발행 성공/실패 카운터를 추가해 운영 대시보드에서 처리량과 에러율을 추적할 수 있게 한다.
package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meter;

    public OutboxRelay(OutboxRepository repo,
                       KafkaTemplate<String,String> kafka,
                       MeterRegistry meter) {
        this.repo = repo;
        this.kafka = kafka;
        this.meter = meter;
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
                meter.counter("outbox.publish.success").increment();
            } catch (Exception ex) {
                e.incrementRetry();
                if (e.getRetryCount() >= MAX_RETRY) e.markDeadLetter();
                meter.counter("outbox.publish.failure").increment();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/inbox/InboxConsumer.java (변경분)
// 처리 건수와 중복 스킵 건수를 카운터로 노출 — Inbox의 멱등성 효과를 수치로 확인할 수 있다.
package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meter;

    public InboxConsumer(InboxRepository inbox,
                         NotificationService notifications,
                         MeterRegistry meter) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.meter = meter;
    }

    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        if (inbox.existsByMessageId(messageId)) {
            meter.counter("inbox.duplicate.count").increment();
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        try {
            inbox.save(InboxEvent.of(messageId, eventType, payload));
            notifications.process(eventType, payload);
            meter.counter("inbox.processed.count").increment();
        } catch (DataIntegrityViolationException dup) {
            meter.counter("inbox.duplicate.count").increment();
            log.info("concurrent duplicate skipped: {}", messageId);
        }
    }
}
```

### 1.3 Actuator 노출 확인

```java
// app/src/test/java/com/hybrid/integration/PrometheusEndpointTest.java
// /actuator/prometheus 응답 본문에 핵심 메트릭이 노출되는지 확인 — 대시보드 연동의 마지막 가드.
package com.hybrid.integration;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointTest extends KafkaIntegrationTestBase {

    @Autowired WebTestClient webTestClient;

    @Test
    void actuator_prometheus_엔드포인트에서_메트릭이_노출된다() {
        String body = webTestClient.get().uri("/actuator/prometheus")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("outbox_pending_count");
        assertThat(body).contains("outbox_publish_success_total");
        assertThat(body).contains("inbox_duplicate_count_total");
    }
}
```

### 1.4 Refactor

- 메트릭 이름을 `MetricNames` 상수 클래스로 모아 오타 방지.
- 이벤트 버스 디스패치 지연은 `Timer`로 측정: `eventbus.dispatch.latency`.

---

## Step 2. Dead Letter Queue — 별도 테이블 + 토픽

Phase 2에서는 상태만 `DEAD_LETTER`로 바꿨다. Phase 3에서는 별도 관리 경로를 만든다.

### 2.1 Red

테스트가 두 개의 책임으로 갈라진다 — common(sweeper 메커니즘)과 app(admin HTTP 엔드포인트). common이 web 의존을 끌고 들어오면 안 되니 분리.

```java
// common/src/test/java/com/hybrid/common/outbox/DeadLetterTest.java
// DEAD_LETTER 행이 outbox_dlq 테이블로 옮겨지는 sweeper 동작만 검증한다.
// admin 엔드포인트의 HTTP 검증은 app/integration/DeadLetterAdminControllerTest로 분리.
package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired DeadLetterRepository dlqRepo;
    @Autowired OutboxRelay relay;
    @Autowired DeadLetterSweeper deadLetterSweeper;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() { kafkaProducerStub.reset(); }

    @Test
    void DEAD_LETTER_상태_이벤트는_dlq_테이블로_이동한다() {
        OutboxEvent e = outboxRepo.save(OutboxEvent.of("Order","42","OrderConfirmed","{}"));
        kafkaProducerStub.alwaysFail();

        for (int i = 0; i < 11; i++) relay.poll();
        deadLetterSweeper.run();

        assertThat(outboxRepo.findById(e.getId())).isEmpty();
        assertThat(dlqRepo.findByOriginalOutboxId(e.getId())).isPresent();
    }
}
```

```java
// app/src/test/java/com/hybrid/integration/DeadLetterAdminControllerTest.java
// admin HTTP 엔드포인트 검증 — WebTestClient는 web 의존이 필요하므로 app 모듈에 위치.
package com.hybrid.integration;

import com.hybrid.common.outbox.DeadLetterEvent;
import com.hybrid.common.outbox.DeadLetterRepository;
import com.hybrid.common.outbox.OutboxEvent;
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeadLetterAdminControllerTest extends KafkaIntegrationTestBase {

    @Autowired DeadLetterRepository dlqRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired WebTestClient webTestClient;

    @Test
    void DLQ_이벤트는_수동_재시도_API로_PENDING으로_복귀시킬_수_있다() {
        DeadLetterEvent dlq = dlqRepo.save(DeadLetterEvent.from(
            OutboxEvent.of("Order","42","OrderConfirmed","{}")));

        webTestClient.post().uri("/admin/dlq/{id}/retry", dlq.getId())
            .exchange().expectStatus().isOk();

        assertThat(dlqRepo.findById(dlq.getId())).isEmpty();
        assertThat(outboxRepo.findAll())
            .anySatisfy(e -> assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING));
    }
}
```

### 2.2 Green

```sql
-- common/src/main/resources/db/migration/V4__outbox_dlq.sql
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
```

```java
// common/src/main/java/com/hybrid/common/outbox/DeadLetterSweeper.java
// 1분 주기로 DEAD_LETTER 행을 감지해 outbox_dlq로 이관하고 원본 outbox 행은 정리한다.
package com.hybrid.common.outbox;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeadLetterSweeper {

    private final OutboxRepository outboxRepo;
    private final DeadLetterRepository dlqRepo;
    private final MeterRegistry meter;

    public DeadLetterSweeper(OutboxRepository outboxRepo,
                             DeadLetterRepository dlqRepo,
                             MeterRegistry meter) {
        this.outboxRepo = outboxRepo;
        this.dlqRepo = dlqRepo;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void run() {
        List<OutboxEvent> dead = outboxRepo.findByStatus(OutboxStatus.DEAD_LETTER);
        for (OutboxEvent e : dead) {
            dlqRepo.save(DeadLetterEvent.from(e));
            outboxRepo.delete(e);
            meter.counter("outbox.deadletter.moved").increment();
        }
    }
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/DeadLetterEvent.java
// 영구 실패한 outbox 이벤트의 보관소. 운영자가 검토 후 수동 재시도 또는 폐기 결정한다.
package com.hybrid.common.outbox;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_dlq")
public class DeadLetterEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long originalOutboxId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private String failureReason;
    private Instant movedAt = Instant.now();

    protected DeadLetterEvent() {}

    public static DeadLetterEvent from(OutboxEvent e) {
        DeadLetterEvent d = new DeadLetterEvent();
        d.originalOutboxId = e.getId();
        d.aggregateType = e.getAggregateType();
        d.aggregateId = e.getAggregateId();
        d.eventType = e.getEventType();
        d.payload = e.getPayload();
        d.failureReason = "max retry exceeded (" + e.getRetryCount() + ")";
        return d;
    }

    public OutboxEvent toRetryable() {
        return OutboxEvent.of(aggregateType, aggregateId, eventType, payload);
    }

    public Long getId() { return id; }
    public Long getOriginalOutboxId() { return originalOutboxId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getFailureReason() { return failureReason; }
    public Instant getMovedAt() { return movedAt; }
}
```

```java
// common/src/main/java/com/hybrid/common/outbox/DeadLetterRepository.java
// DLQ 테이블 영속 추상. 원본 outbox id로 추적 가능하게 한다.
package com.hybrid.common.outbox;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetterEvent, Long> {
    Optional<DeadLetterEvent> findByOriginalOutboxId(Long outboxId);
}
```

**왜 `common`이 아니라 `app`에 두는가**

- `common`은 web 의존성(`spring-boot-starter-webmvc`)을 **의도적으로 갖지 않는다** — 인프라 모듈이지 HTTP 노출 자리가 아님. `ResponseEntity`, `@RestController`는 거기서 컴파일 안 됨.
- 운영 관리(admin) 엔드포인트는 **조립체(app)의 책임**. 도메인이 아니고, 인프라도 아닌 "운영자가 시스템과 대화하는 UI 레이어".
- 미래에 admin 기능이 늘면 별도 `admin-service` 모듈로 분리하기도 쉬움 — common·도메인 모듈 영향 없이.

```java
// app/src/main/java/com/hybrid/admin/DeadLetterAdminController.java
// 운영자용 수동 재시도 엔드포인트. DLQ 행을 PENDING outbox로 되돌린다.
// app 모듈 — common이 web 의존성 없으므로 여기 위치.
package com.hybrid.admin;

import com.hybrid.common.outbox.DeadLetterRepository;
import com.hybrid.common.outbox.OutboxRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dlq")
public class DeadLetterAdminController {

    private final DeadLetterRepository dlqRepo;
    private final OutboxRepository outboxRepo;

    public DeadLetterAdminController(DeadLetterRepository dlqRepo, OutboxRepository outboxRepo) {
        this.dlqRepo = dlqRepo;
        this.outboxRepo = outboxRepo;
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        return dlqRepo.findById(id)
                .map(dlq -> {
                    outboxRepo.save(dlq.toRetryable());
                    dlqRepo.delete(dlq);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

이제 `DeadLetterTest`(common)가 `WebTestClient`로 이 엔드포인트를 호출하려면 다음 중 하나를 선택:

1. **테스트를 `app/integration`으로 이동** — admin endpoint도 app, 통합 검증도 app이 자연스럽다.
2. common에서는 **DeadLetterRepository만 직접 검증**(웹 호출 부분 제거), admin endpoint 자체의 HTTP 동작은 별도 `app/integration/DeadLetterAdminControllerTest`에서 검증.

이 프로젝트는 옵션 2를 권장 — common 테스트가 web 의존을 끌고 들어오는 건 모듈 사상에 어긋난다.

### 2.3 Refactor

- DLQ 테이블 쌓임은 알림 대상이어야 한다 → `outbox.deadletter.count` 게이지가 0을 넘으면 알람 조건(운영 문서화).

---

## Step 3. Outbox 테이블 클린업

### 3.1 Red

```java
// common/src/test/java/com/hybrid/common/outbox/OutboxCleanerTest.java
// 발행된 지 7일 이상 경과한 outbox 행만 삭제되고 최근 행은 보존되는지 확인한다.
package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCleanerTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired OutboxCleaner cleaner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 발행된지_7일_이상된_outbox_레코드는_삭제된다() {
        OutboxEvent old = outboxRepo.save(OutboxEvent.of("Order","1","t","{}"));
        old.markPublished();
        jdbc.update("UPDATE outbox SET published_at = NOW() - INTERVAL '8 days' WHERE id = ?",
            old.getId());

        OutboxEvent recent = outboxRepo.save(OutboxEvent.of("Order","2","t","{}"));
        recent.markPublished();

        cleaner.run();

        assertThat(outboxRepo.findById(old.getId())).isEmpty();
        assertThat(outboxRepo.findById(recent.getId())).isPresent();
    }
}
```

### 3.2 Green

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxCleaner.java
// 매일 새벽 3시에 PUBLISHED 상태의 오래된 outbox 행을 일괄 삭제해 테이블 비대화를 막는다.
package com.hybrid.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final JdbcTemplate jdbc;

    public OutboxCleaner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void run() {
        int deleted = jdbc.update("""
            DELETE FROM outbox
            WHERE status = 'PUBLISHED'
              AND published_at < NOW() - INTERVAL '7 days'
        """);
        log.info("outbox cleanup: deleted {} rows", deleted);
    }
}
```

---

## Step 4. 인메모리 이벤트 버스 장애 복구

JVM 크래시 후 재시작 시 "결제는 됐는데 주문이 아직 PAYMENT_PENDING"인 상황을 복구한다.

**모듈 경계 결정** — `OrderRecoveryJob`은 `Order`와 `Payment` 두 도메인을 동시에 다룬다. order 모듈에 두면 `payment.domain.*`을 import해야 해서 모듈 경계 위반. 두 가지 선택지:

| 옵션 | 평가 |
|------|------|
| **A. app 모듈로 이동** (선택) | 운영 orchestration은 조립체의 책임. `DeadLetterAdminController`와 같은 사상 |
| B. 메시지 기반 (RecoveryRequested 발행, payment 응답) | MSA 분리 시 그대로 가지만 학습 단계에선 과한 복잡도 |

옵션 A로 간다.

### 4.1 Red

```java
// app/src/test/java/com/hybrid/integration/EventBusCrashRecoveryTest.java
// JVM 크래시 시나리오에서 OrderRecoveryJob이 stuck 주문을 복구하는지 검증.
// 두 도메인을 함께 다루므로 app/integration 위치.
// 가짜 헬퍼(awaitPaymentCompleted, forceStatus) 대신 실제 Phase 1 체인 + JdbcTemplate으로 상태 강제.
package com.hybrid.integration;

import java.math.BigDecimal;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;
import com.hybrid.recovery.OrderRecoveryJob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventBusCrashRecoveryTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired OrderRecoveryJob recoveryJob;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 결제_완료_후_JVM_크래시_시나리오에서_재시작하면_주문이_CONFIRMED로_복구된다() {
        // [Arrange] Phase 1 체인 자동 실행 — 주문 생성 → 결제 처리
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));

        await().atMost(3, SECONDS).untilAsserted(() ->
            assertThat(paymentRepo.findByOrderIdAndStatus(orderId, PaymentStatus.COMPLETED))
                .isPresent());

        // [Act 1] 크래시 시뮬레이션:
        //  - Order 상태를 PAYMENT_PENDING으로 강제
        //  - created_at을 5분 전으로 당김 (recoveryJob의 60초 임계 통과)
        jdbc.update(
            "UPDATE orders SET status = ?, created_at = NOW() - INTERVAL '5 minutes' WHERE id = ?",
            OrderStatus.PAYMENT_PENDING.name(), orderId);

        // [Act 2] 복구 잡 직접 실행
        recoveryJob.run();

        // [Assert] 주문이 CONFIRMED로 회복됨
        assertThat(orderRepo.findById(orderId).orElseThrow().status())
            .isEqualTo(OrderStatus.CONFIRMED);
    }
}
```

### 4.2 Green — DB 상태 기반 복구

```java
// app/src/main/java/com/hybrid/recovery/OrderRecoveryJob.java
// 여러 도메인을 가로지르는 운영 orchestration — app/recovery 패키지에 위치.
// 도메인 객체의 confirm()을 직접 부르지 않고 OrderService.confirm()을 호출 — Phase 2의 outbox INSERT 우회 방지.
package com.hybrid.recovery;

import java.time.Instant;
import java.util.List;

import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    private final OrderRepository orderRepo;
    private final OrderService orderService;
    private final PaymentRepository paymentRepo;
    private final MeterRegistry meter;

    public OrderRecoveryJob(OrderRepository orderRepo,
                            OrderService orderService,
                            PaymentRepository paymentRepo,
                            MeterRegistry meter) {
        this.orderRepo = orderRepo;
        this.orderService = orderService;
        this.paymentRepo = paymentRepo;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void run() {
        List<Order> stuck = orderRepo.findByStatusAndCreatedAtBefore(
            OrderStatus.PAYMENT_PENDING, Instant.now().minusSeconds(60));

        for (Order o : stuck) {
            paymentRepo.findByOrderIdAndStatus(o.getId(), PaymentStatus.COMPLETED)
                .ifPresent(p -> {
                    log.warn("recovering stuck order {}", o.getId());
                    orderService.confirm(o.getId());   // outbox INSERT 포함된 정식 경로
                    meter.counter("order.recovered").increment();
                });
        }
    }
}
```

### 4.3 Refactor

- 복구 기준 시간(60초)을 설정으로. 너무 짧으면 정상 흐름과 경쟁, 너무 길면 사용자 체감 지연.

---

## Step 5. Circuit Breaker — 외부 알림 서비스 연동

### 5.1 Red

```java
// notification/src/test/java/com/hybrid/notification/service/NotificationCircuitBreakerTest.java
// 외부 발송 실패가 누적되면 회로가 OPEN되어 빠르게 실패하고, 이후 HALF_OPEN으로 복구되는지 검증한다.
package com.hybrid.notification.service;

import java.time.Duration;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.notification.support.EmailSenderStub;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(EmailSenderStub.Config.class)   // EmailGateway 자리에 stub 주입
class NotificationCircuitBreakerTest extends KafkaIntegrationTestBase {

    @Autowired NotificationService notificationService;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired EmailSenderStub emailSenderStub;

    @AfterEach
    void resetStubAndCircuit() {
        emailSenderStub.reset();
        circuitBreakerRegistry.circuitBreaker("email").reset();
    }

    @Test
    void EmailSender가_연속_실패하면_Circuit이_OPEN되고_빠르게_실패한다() {
        emailSenderStub.alwaysFail();

        for (int i = 0; i < 10; i++) {
            try { notificationService.process("OrderConfirmed", "{}"); }
            catch (Exception ignored) {}
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("email");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        assertThatThrownBy(() -> notificationService.process("OrderConfirmed", "{}"))
            .isInstanceOf(CallNotPermittedException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(50);   // 빠른 실패
    }

    @Test
    void OPEN_상태_이후_일정_시간이_지나면_HALF_OPEN으로_전환된다() throws InterruptedException {
        emailSenderStub.alwaysFail();
        for (int i = 0; i < 10; i++) {
            try { notificationService.process("OrderConfirmed", "{}"); }
            catch (Exception ignored) {}
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("email");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(11_000);   // waitDurationInOpenState(10s) + 여유

        emailSenderStub.alwaysSucceed();
        try { notificationService.process("OrderConfirmed", "{}"); }
        catch (Exception ignored) {}

        assertThat(cb.getState()).isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
    }
}
```

### 5.2 Green — Resilience4j

```groovy
implementation 'io.github.resilience4j:resilience4j-spring-boot3'
```

```java
// notification/src/main/java/com/hybrid/notification/service/EmailSender.java
// Resilience4j @CircuitBreaker로 외부 메일 시스템 장애가 전체 처리를 막지 않도록 격벽을 친다.
package com.hybrid.notification.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final EmailGateway gateway;
    private final DeferredEmailQueue deferred;

    public EmailSender(EmailGateway gateway, DeferredEmailQueue deferred) {
        this.gateway = gateway;
        this.deferred = deferred;
    }

    @CircuitBreaker(name = "email", fallbackMethod = "fallback")
    public void send(String to, String content) {
        gateway.deliver(to, content);   // 외부 SMTP/SaaS 호출 — 실패 가능성
    }

    void fallback(String to, String content, Throwable t) {
        log.warn("email circuit open, message deferred: {}", to, t);
        deferred.enqueue(new DeferredEmail(to, content));   // 회복 후 재발송 대상
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/EmailGateway.java
// 외부 메일 시스템 호출 추상화. 테스트에서는 Stub으로 교체해 실패 시나리오를 만든다.
package com.hybrid.notification.service;

public interface EmailGateway {
    void deliver(String to, String content);
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/LoggingEmailGateway.java
// 운영 기본 EmailGateway 구현 — Phase 3 학습 단계엔 로그만. 실제 운영에선 SMTP/SaaS 어댑터로 교체.
// @Profile("!test")로 테스트 환경에선 EmailSenderStub이 우선되도록 분기.
package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);

    @Override
    public void deliver(String to, String content) {
        log.info("[email] to={} content={}", to, content);
    }
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/DeferredEmail.java
// Circuit이 OPEN인 동안 대기시킬 메일 한 건의 불변 표현.
package com.hybrid.notification.service;

import java.time.Instant;

public record DeferredEmail(String to, String content, Instant queuedAt) {
    public DeferredEmail(String to, String content) { this(to, content, Instant.now()); }
}
```

```java
// notification/src/main/java/com/hybrid/notification/service/DeferredEmailQueue.java
// Circuit OPEN 동안 잠시 보류된 메일들을 회복 후 재발송 대상으로 보관한다.
package com.hybrid.notification.service;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeferredEmailQueue {

    private static final Logger log = LoggerFactory.getLogger(DeferredEmailQueue.class);

    private final Queue<DeferredEmail> queue = new ConcurrentLinkedQueue<>();
    private final EmailSender sender;

    public DeferredEmailQueue(EmailSender sender) { this.sender = sender; }

    public void enqueue(DeferredEmail email) { queue.add(email); }

    public int size() { return queue.size(); }

    @Scheduled(fixedDelay = 30_000)
    public void drain() {
        DeferredEmail e;
        while ((e = queue.poll()) != null) {
            try {
                sender.send(e.to(), e.content());        // Circuit이 닫혔으면 정상 발송
            } catch (Exception ex) {
                log.warn("re-deferring email to {}", e.to(), ex);
                queue.add(e);                            // 여전히 OPEN이면 다시 보관
                return;                                  // 한 건 실패하면 다음 사이클로
            }
        }
    }

    public Optional<DeferredEmail> peek() { return Optional.ofNullable(queue.peek()); }
}
```

**`application.yml` 위치 — 어디에 둘까**

| 시나리오 | yml 위치 |
|---------|--------|
| 모놀리스 실행 (`./gradlew :app:bootRun`) | `app/src/main/resources/application.yml` |
| Notification 단독 실행 (Step 6.3) | `notification/src/main/resources/application.yml` |

`@SpringBootApplication`이 실행되는 모듈의 classpath만 yml을 로드한다. 모놀리스 모드에선 notification의 클래스가 app의 yml을 읽는다.

```yaml
# app/src/main/resources/application.yml에 추가
resilience4j:
  circuitbreaker:
    instances:
      email:
        failureRateThreshold: 50            # 실패율 50% 넘으면 OPEN
        slidingWindowSize: 10               # 최근 10 호출 윈도우
        waitDurationInOpenState: 10s        # OPEN 유지 시간 → 이후 HALF_OPEN
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5             # 윈도우에 최소 이만큼 차야 평가
        automaticTransitionFromOpenToHalfOpenStateEnabled: true
```

**테스트용 EmailSenderStub** — Circuit Breaker 동작을 결정적으로 검증하기 위해 EmailGateway 자리를 stub으로 대체.

```java
// notification/src/test/java/com/hybrid/notification/support/EmailSenderStub.java
// 외부 메일 발송 실패를 결정적으로 시뮬레이션하는 스텁.
// EmailGateway를 구현해 production EmailGateway(LoggingEmailGateway)를 @Primary로 대체.
package com.hybrid.notification.support;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.hybrid.notification.service.EmailGateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

public class EmailSenderStub implements EmailGateway {

    private final AtomicBoolean alwaysFail = new AtomicBoolean(false);
    private final AtomicInteger callCount = new AtomicInteger();

    public void alwaysFail() { alwaysFail.set(true); }
    public void alwaysSucceed() { alwaysFail.set(false); }

    public void reset() {
        alwaysFail.set(false);
        callCount.set(0);
    }

    public int callCount() { return callCount.get(); }

    @Override
    public void deliver(String to, String content) {
        callCount.incrementAndGet();
        if (alwaysFail.get()) {
            throw new RuntimeException("email gateway stub: alwaysFail");
        }
    }

    /** 테스트가 @Import로 끌어들여 production gateway를 대체. */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public EmailSenderStub emailSenderStub() {
            return new EmailSenderStub();
        }

        @Bean
        @Primary
        public EmailGateway stubGateway(EmailSenderStub stub) {
            return stub;
        }
    }
}
```

테스트는 `@Import(EmailSenderStub.Config.class)`로 스텁을 끌어들인다 — 5.1의 코드 참조.

---

## Step 6. Notification 독립 실행 검증

"Notification 모듈이 다른 모듈에 코드 의존성을 갖지 않는다"를 테스트로 증명한다.

### 6.1 Red — 모듈 의존성 테스트 (ArchUnit)

```groovy
// notification/build.gradle
testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
```

```java
// notification/src/test/java/com/hybrid/notification/standalone/NotificationModuleBoundaryTest.java
// ArchUnit으로 Notification이 order/payment 패키지에 컴파일 의존을 갖지 않음을 강제한다.
package com.hybrid.notification.standalone;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.hybrid.notification")
class NotificationModuleBoundaryTest {

    @ArchTest
    static final ArchRule notification은_order_payment에_의존하지_않는다 =
        noClasses().that().resideInAPackage("com.hybrid.notification..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.hybrid.order..", "com.hybrid.payment..");

    @ArchTest
    static final ArchRule 통신은_kafka_inbox를_통해서만 =
        classes().that().resideInAPackage("com.hybrid.notification..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "com.hybrid.notification..",
                "com.hybrid.common.event..",
                "java..", "jakarta..", "org.springframework..",
                "org.apache.kafka..", "com.fasterxml..", "org.slf4j..");
}
```

### 6.2 Green

실패하는 곳이 있으면 인터페이스를 common으로 뽑거나, 의존을 제거한다.

### 6.3 Red — Notification만 독립 기동

```java
// notification/src/test/java/com/hybrid/notification/standalone/NotificationStandaloneTest.java
// NotificationApplication만 단독 부팅했을 때 Kafka 메시지 입력만으로 동작이 완결되는지 확인한다.
package com.hybrid.notification.standalone;

import java.util.Map;

import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.NotificationApplication;
import com.hybrid.notification.inbox.InboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = NotificationApplication.class,
    properties = { "spring.main.web-application-type=none" })
@Testcontainers
class NotificationStandaloneTest {

    @Container static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired KafkaTestProducer producer;
    @Autowired InboxRepository inboxRepo;

    @Test
    void Notification은_Order_Payment없이_Kafka_메시지만으로_동작한다() {
        producer.send("order-events", "42",
            "{\"orderId\":42}", Map.of("messageId","m1","eventType","OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(inboxRepo.existsByMessageId("m1")).isTrue());
    }
}
```

### 6.4 Green

```java
// notification/src/main/java/com/hybrid/notification/NotificationApplication.java
// Notification 모듈을 별도 Spring Boot 애플리케이션으로 부팅하기 위한 진입점.
package com.hybrid.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
```

전용 `application-notification.yml`에 자기 DB만 설정.

---

## Step 7. Multi-Instance Relay 안전성

Phase 2에서 보류했던 멀티 인스턴스 Relay를 TDD로 증명.

### 7.1 Red

```java
// common/src/test/java/com/hybrid/common/outbox/MultiInstanceRelayTest.java
// 두 릴레이 인스턴스가 동시에 폴링해도 같은 outbox 행이 중복 발행되지 않음을 증명한다.
package com.hybrid.common.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MultiInstanceRelayTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired KafkaTestConsumer consumer;

    @Test
    void 두_개의_Relay가_동시에_poll해도_같은_이벤트를_두_번_발행하지_않는다() throws Exception {
        for (int i = 0; i < 50; i++)
            outboxRepo.save(OutboxEvent.of("Order", String.valueOf(i), "OrderConfirmed", "{}"));

        OutboxRelay r1 = newRelayInstance();
        OutboxRelay r2 = newRelayInstance();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(() -> { go.await(); r1.poll(); return null; });
        Future<?> f2 = pool.submit(() -> { go.await(); r2.poll(); return null; });

        go.countDown();
        f1.get(); f2.get();

        List<ConsumerRecord<String,String>> sent = consumer.drain("order-events", Duration.ofSeconds(5));
        Set<String> messageIds = sent.stream()
            .map(r -> new String(r.headers().lastHeader("messageId").value()))
            .collect(Collectors.toSet());
        assertThat(messageIds).hasSize(sent.size());    // 중복 없음
    }

    @Autowired OutboxRepository outboxRepository;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String,String> kafkaTemplate;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * 같은 Repository / KafkaTemplate / MeterRegistry를 공유하지만
     * 별도 OutboxRelay 인스턴스를 만든다.
     * Spring 컨테이너의 단일 빈을 두 번 받는 게 아니라,
     * "두 노드가 같은 DB·Kafka에 동시에 붙은 상황"을 시뮬레이션한다.
     */
    private OutboxRelay newRelayInstance() {
        return new OutboxRelay(outboxRepository, kafkaTemplate, meterRegistry);
    }
}
```

### 7.2 Green — `FOR UPDATE SKIP LOCKED`

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRepository.java
// FOR UPDATE SKIP LOCKED 네이티브 쿼리로 행 단위 잠금을 잡아 멀티 인스턴스 릴레이를 안전하게 만든다.
package com.hybrid.common.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    long countByStatus(OutboxStatus status);
    List<OutboxEvent> findByStatus(OutboxStatus status);

    @Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("batchSize") int batchSize);
}
```

**`OutboxRelay.poll`을 행 잠금 기반으로 교체.** Phase 2에서 PENDING 행을 그냥 `findTop100ByStatusOrderByCreatedAtAsc`로 가져왔는데, 멀티 인스턴스 환경에선 두 노드가 같은 행을 동시에 가져가 **중복 발행**할 수 있다. `lockPending(BATCH)` 네이티브 쿼리는 PostgreSQL의 `FOR UPDATE SKIP LOCKED`로 **이미 잠긴 행을 건너뛰고** 다음 행을 잡으니 한 행은 한 노드만 처리한다.

```java
// common/src/main/java/com/hybrid/common/outbox/OutboxRelay.java (변경분)
// findTop100ByStatusOrderByCreatedAtAsc → lockPending(BATCH)로 교체.
// @Transactional이 종료될 때 (정상 커밋 또는 롤백) 잡힌 행 잠금이 자동 해제된다.
package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meter;

    public OutboxRelay(OutboxRepository repo,
                       KafkaTemplate<String,String> kafka,
                       MeterRegistry meter) {
        this.repo = repo;
        this.kafka = kafka;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        // 변경 전: List<OutboxEvent> pending = repo.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        // 변경 후: 행 단위 잠금으로 멀티 인스턴스 안전 확보
        List<OutboxEvent> pending = repo.lockPending(BATCH);

        for (OutboxEvent e : pending) {
            try {
                ProducerRecord<String,String> record = new ProducerRecord<>(
                    TOPIC, e.getAggregateId(), e.getPayload());
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                record.headers().add("eventType", e.getEventType().getBytes());

                kafka.send(record).get(5, TimeUnit.SECONDS);
                e.markPublished();
                meter.counter("outbox.publish.success").increment();
            } catch (Exception ex) {
                e.incrementRetry();
                if (e.getRetryCount() >= MAX_RETRY) e.markDeadLetter();
                meter.counter("outbox.publish.failure").increment();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
        // [트랜잭션 종료]
        //   - 메서드 정상 종료 → 커밋 → markPublished/markDeadLetter 반영 + 행 잠금 해제
        //   - 예외 전파 → 롤백 → 변경 폐기 + 행 잠금 해제 (다음 폴링이 다시 잡음)
    }
}
```

### 7.3 동작 시각화 — 두 노드가 동시에 polling

```
[T+0]  outbox 테이블에 PENDING 행 100건

[T+1]  Node A: BEGIN TRANSACTION
              lockPending(100)
              → SELECT ... FROM outbox WHERE status='PENDING'
                ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
              → 행 1~100을 잠그고 반환

[T+1.1] Node B: BEGIN TRANSACTION
              lockPending(100)
              → 같은 쿼리 실행
              → 1~100은 이미 Node A에 잠김 → SKIP
              → 행 101~200을 잠그고 반환  (PENDING이 더 있으면)
              → PENDING이 더 없으면 빈 리스트

[T+5]  Node A: 100건 발행, markPublished() 100번
              COMMIT → 잠금 해제 + 상태 PUBLISHED 반영

[T+5.1] Node B: (PENDING이 더 있었던 경우) 자기 100건 발행
              COMMIT → 잠금 해제 + 상태 PUBLISHED 반영

결과: 같은 행을 두 번 발행하는 일 없음.
```

### 7.4 주의 — 잠금 보유 시간

`@Transactional` 안에서 `kafka.send(...).get(5초)`를 100번 동기 호출하면 트랜잭션이 **최대 8분** 잠금을 들고 있을 수 있다(매우 이론적). 실제론 send가 ms 단위라 거의 무관하지만, 더 안전하게 가려면:

- **BATCH를 작게** (예: 20) — 잠금 보유 시간 제한.
- **send 결과는 비동기로 받고 그 안에서 별도 짧은 트랜잭션으로 상태 갱신** — 잠금은 fetch + 즉시 commit.
- 두 번째 옵션은 구조가 복잡해지므로 학습 단계엔 BATCH=100 + 동기 send + 단일 트랜잭션이 충분.

---

## Step 8. Order ↔ Payment 분리 로드맵 (문서화)

Phase 3에서는 실제 분리를 하지 않는다. 테스트가 아니라 **문서 산출물**을 남긴다.

### 8.1 산출물

`docs/roadmap-order-payment-split.md`에 다음 항목을 포함:

1. 전환 전/후 다이어그램
2. 이벤트 스키마 호환성 체크리스트 (인메모리 이벤트 = Kafka 이벤트 페이로드)
3. DB 분리 전략: 공유 테이블 식별, `orders` / `payments` 분리 시점
4. 단계적 전환 시나리오:
   - S1: Payment 모듈에 Outbox/Inbox 추가 (인메모리 + Kafka 듀얼 발행)
   - S2: Order가 Kafka 구독으로 전환 (인메모리 구독 비활성화)
   - S3: Payment를 별도 Spring Boot 앱으로 분리
5. 각 단계별 **롤백 기준**과 **자동화된 검증 테스트**

이 문서 작성 자체는 코드 테스트가 아니지만, **"작성되어 있다"를 CI에서 확인하는 테스트**를 둔다:

```java
// app/src/test/java/com/hybrid/integration/RoadmapDocumentTest.java
// 분리 로드맵 문서가 존재하고 필수 섹션을 포함하는지 CI에서 강제 — 문서가 코드와 함께 진화하도록 한다.
package com.hybrid.integration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapDocumentTest {

    @Test
    void 분리_로드맵_문서가_존재하고_필수_섹션을_포함한다() throws Exception {
        Path doc = Path.of("docs/roadmap-order-payment-split.md");
        assertThat(Files.exists(doc)).isTrue();
        String content = Files.readString(doc);
        assertThat(content).contains("## 전환 전/후");
        assertThat(content).contains("## 단계별 시나리오");
        assertThat(content).contains("## 롤백 기준");
    }
}
```

---

## Step 9. Chaos / 운영 시나리오 E2E

### 9.1 Red

```java
// app/src/test/java/com/hybrid/integration/ChaosScenarioTest.java
// 부하 중 Kafka 일시 중단/재기동을 견디며 모든 알림이 결국 발송되는 시스템 전반의 자가복구를 검증한다.
package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ChaosScenarioTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OutboxRepository outboxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() {
        kafkaProducerStub.reset();
    }

    @Test
    void 주문_100건_처리_중_발행이_5초간_막혔다_풀려도_모든_알림이_발송된다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Long id = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
            orderService.confirm(id);
            ids.add(id);
        }

        // 발행 차단 시뮬레이션 — KafkaContainer를 직접 stop/start하지 않는다.
        // 컨테이너 재시작은 bootstrap URL 변경 / Spring 컨텍스트 캐시 영향 등 비결정 요소가 많다.
        // KafkaProducerStub.alwaysFail은 "Kafka가 계속 실패한다"를 결정적으로 시뮬레이션한다.
        kafkaProducerStub.alwaysFail();
        Thread.sleep(5_000);
        kafkaProducerStub.reset();

        await().atMost(60, SECONDS).untilAsserted(() -> {
            assertThat(outboxRepo.countByStatus(OutboxStatus.PENDING)).isZero();
            assertThat(outboxRepo.countByStatus(OutboxStatus.DEAD_LETTER)).isZero();
            for (Long id : ids)
                assertThat(notificationRepo.findByOrderId(id)).isPresent();
        });
    }
}
```

이 테스트가 안정적으로 통과하면 Phase 3의 실질적 안정성이 증명된다.

> **컨테이너 재시작 vs Stub 실패 — 트레이드오프**
>
> 진짜로 `kafka.stop() / kafka.start()`로 컨테이너 자체를 내렸다 올리는 chaos 테스트는 가능하지만 다음을 모두 처리해야 한다:
> - 재시작 후 bootstrap port가 바뀌면 Spring producer 재설정 필요 (`@DynamicPropertySource`만으로는 부족 — 컨텍스트가 이미 부팅됨).
> - `KafkaListener` 컨슈머도 재연결 필요.
> - 다른 테스트와 컨테이너 캐시 충돌.
>
> 이 모두를 풀려면 `kafka-test` 라이브러리의 `EmbeddedKafkaBroker`나 별도 chaos 도구(Toxiproxy 등)가 필요하다.
> 학습 단계의 Phase 3에선 **`KafkaProducerStub.alwaysFail()`로 의미적으로 동등한 결과**를 얻고, 진짜 컨테이너 chaos는 운영 단계의 별도 테스트로 분리하는 게 실용적이다.

---

## Phase 3 완료 체크리스트

| 완료 기준 | 검증 수단 |
|----------|---------|
| 핵심 메트릭이 대시보드에 노출된다 | `OutboxMetricsTest`(common) + `InboxConsumerMetricsTest`(notification) + `PrometheusEndpointTest`(app) + Grafana JSON 파일 커밋 |
| JVM 크래시 후 재시작 시 미완료 주문 복구 | `EventBusCrashRecoveryTest` |
| Notification 모듈 독립 실행 가능 | `NotificationModuleBoundaryTest` + `NotificationStandaloneTest` |
| Order ↔ Payment 분리 로드맵 문서 존재 | `RoadmapDocumentTest` |
| DLQ로 실패 이벤트 관리 | `DeadLetterTest` |
| 운영 시나리오 안정성 | `ChaosScenarioTest` |

---

## 전체 회귀

Phase 3 어떤 작업도 Phase 1/2의 테스트를 깨면 안 된다.

```bash
./gradlew test                       # 전체
./gradlew :common:test :order:test :payment:test    # Phase 1
./gradlew :notification:test                         # Phase 2/3
./gradlew integrationTest            # Testcontainers 포함 시나리오
```

---

## 마치며

Phase 3의 끝에서는 다음이 **테스트로 증명**된다.

1. 이벤트 버스의 상태를 외부에서 관측할 수 있다 (메트릭).
2. 장애가 나도 데이터는 유실되지 않고, 복구는 자동이다 (Recovery / Retry / DLQ).
3. Notification은 Kafka만으로 살아간다 (독립 실행).
4. 같은 인터페이스(이벤트)로 통신하므로, 실제 분리 시 코드 변경은 **통신 계층 교체에 국한**된다 (로드맵 문서).

이 4가지가 "하이브리드 이벤트 드리븐"이라는 이름이 실제로 의미하는 것이다.
