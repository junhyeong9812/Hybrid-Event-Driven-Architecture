package com.hybrid.common.outbox;

// === 표준 라이브러리 ===
// Duration: Kafka consumer.poll(timeout)에 넘길 시간 단위
import java.time.Duration;
// List: drain()이 여러 메시지를 한 번에 반환할 때 사용
import java.util.List;
// TimeoutException: Kafka 발행 실패 시나리오를 시뮬레이션할 때 던질 예외
import java.util.concurrent.TimeoutException;

// === 프로젝트 테스트 인프라 (common/testFixtures) ===
// PostgreSQL + Kafka Testcontainer를 자동 기동·정리해주는 공통 베이스
import com.hybrid.common.support.KafkaIntegrationTestBase;
// 테스트가 직접 토픽을 구독해 메시지를 검증할 때 쓰는 헬퍼 빈
import com.hybrid.common.support.KafkaTestConsumer;
// 실패 주입(failNext, alwaysFail)이 가능한 KafkaTemplate 대체 구현
import com.hybrid.common.support.KafkaProducerStub;

// === Kafka 클라이언트 ===
// 컨슈머가 토픽에서 받아낸 메시지 한 건의 표현 (key, value, headers 포함)
import org.apache.kafka.clients.consumer.ConsumerRecord;

// === JUnit 5 ===
// 각 @Test 메서드에 붙는 표시
import org.junit.jupiter.api.Test;

// === Spring ===
// 컨테이너에서 빈을 주입받기 위한 표시
import org.springframework.beans.factory.annotation.Autowired;

// === AssertJ (정적 import) ===
// 가독성 좋은 fluent 단언: assertThat(actual).isEqualTo(expected)
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxRelay의 핵심 계약 세 가지를 검증한다:
 *  1. PENDING outbox 행을 Kafka로 발행하고 상태를 PUBLISHED로 갱신한다.
 *  2. 발행 실패 시 retryCount를 누적하고 상태는 PENDING으로 유지한다.
 *  3. 같은 행을 여러 번 polling해도 두 번 발행하지 않는다 (멱등).
 *
 * KafkaIntegrationTestBase를 상속받으므로 PostgreSQL + Kafka 컨테이너가 자동으로 떠 있다.
 */
class OutboxRelayTest extends KafkaIntegrationTestBase {

    // 진짜 OutboxRepository 빈 — 테스트가 직접 outbox 행을 INSERT/조회해 검증한다.
    @Autowired OutboxRepository outboxRepo;

    // 검증 대상인 OutboxRelay 빈 — 운영 코드와 동일한 인스턴스.
    @Autowired OutboxRelay relay;

    // 테스트 전용 Kafka 컨슈머 헬퍼.
    // KafkaIntegrationTestBase가 띄운 KafkaContainer에 연결되어, 토픽에서 메시지를 직접 받아온다.
    @Autowired KafkaTestConsumer consumer;

    // 실제 KafkaTemplate을 대체하는 stub.
    // failNext / alwaysFail 같은 메서드로 발행 실패 시나리오를 의도적으로 만들 수 있다.
    @Autowired KafkaProducerStub kafkaProducerStub;

    /**
     * 정상 흐름:
     *   PENDING outbox 행이 있으면 → relay.poll() 호출 → Kafka에 메시지가 발행되고
     *   → outbox 행은 PUBLISHED로 전이되며 publishedAt이 채워진다.
     */
    @Test
    void PENDING_상태_이벤트를_Kafka로_발행하고_PUBLISHED로_변경한다() {
        // [Arrange] outbox 테이블에 PENDING 행을 한 개 넣는다.
        // OutboxEvent.of(...)는 status=PENDING, retryCount=0, createdAt=now()로 자동 초기화한다.
        OutboxEvent saved = outboxRepo.save(OutboxEvent.of(
                "Order", "42", "OrderConfirmed", "{\"orderId\":42}"));

        // [Act] @Scheduled를 기다리지 않고 직접 polling 메서드를 호출.
        // 스케줄링은 운영 시 1초마다 동작하지만, 테스트에선 즉시 한 번 강제 실행한다.
        relay.poll();

        // [Assert 1] Kafka 토픽 "order-events"에 발행됐는지 — 최대 5초 대기하며 한 건 받아온다.
        // 발행 자체는 비동기지만 Testcontainers 환경에선 보통 ms 단위로 완료된다.
        ConsumerRecord<String,String> msg = consumer.poll("order-events", Duration.ofSeconds(5));
        // 메시지가 실제로 도착했음을 확인 (null이면 polling 타임아웃 — 발행 실패).
        assertThat(msg).isNotNull();
        // 메시지 key는 aggregateId("42") — 같은 주문의 이벤트들이 같은 파티션에 모이도록.
        assertThat(msg.key()).isEqualTo("42");
        // 메시지 본문(payload)에 이벤트 타입 문자열이 포함됨 — 직렬화 정상 확인.
        assertThat(msg.value()).contains("OrderConfirmed");

        // [Assert 2] DB의 outbox 행 상태도 갱신됐는지 — 다시 조회.
        OutboxEvent reloaded = outboxRepo.findById(saved.getId()).orElseThrow();
        // PENDING → PUBLISHED 전이 확인.
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        // publishedAt 타임스탬프가 채워졌는지 — markPublished()가 호출됐다는 증거.
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    /**
     * 실패 흐름:
     *   Kafka 발행이 TimeoutException으로 실패하면 → 상태는 PENDING 유지 + retryCount 1 증가.
     *   다음 polling 사이클에서 다시 시도되도록 한다 (at-least-once 발행 보장).
     */
    @Test
    void 발행_실패_시_retry_count가_증가한다() {
        // [Arrange] PENDING 행을 한 개 넣는다.
        OutboxEvent saved = outboxRepo.save(OutboxEvent.of(
                "Order", "42", "OrderConfirmed", "{\"orderId\":42}"));

        // [Arrange] 다음 send() 호출이 TimeoutException으로 실패하도록 stub 설정.
        // "broker down"은 메시지일 뿐 — 동작에 영향 없다.
        kafkaProducerStub.failNext(new TimeoutException("broker down"));

        // [Act] polling 한 번. 내부에서 send → catch (TimeoutException) → incrementRetry 흐름.
        relay.poll();

        // [Assert] outbox 행을 다시 조회해 상태 변화를 확인.
        OutboxEvent reloaded = outboxRepo.findById(saved.getId()).orElseThrow();
        // 발행 실패니 PUBLISHED로 가지 않고 PENDING에 남아 있어야 함.
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        // retryCount가 정확히 1 증가했는지 — incrementRetry()가 한 번 호출됐다는 증거.
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
    }

    /**
     * 멱등성:
     *   같은 PENDING 행이 있더라도 polling 여러 번 호출 시
     *   첫 번째 호출에서만 실제 발행이 일어나고, 이후엔 PUBLISHED 상태라 발행 대상이 아니다.
     *   같은 메시지가 Kafka에 두 번 이상 들어가면 안 된다.
     */
    @Test
    void 여러_번_poll해도_같은_메시지를_두_번_발행하지_않는다() {
        // [Arrange] PENDING 행 하나.
        outboxRepo.save(OutboxEvent.of("Order","42","OrderConfirmed","{}"));

        // [Act] 일부러 세 번 호출.
        // 첫 호출: 발행 + PUBLISHED 전이.
        // 둘째·셋째: PENDING 행이 없어 아무 일도 안 함.
        relay.poll();
        relay.poll();
        relay.poll();

        // [Assert] Kafka 토픽에 도착한 모든 메시지를 2초 동안 끌어모은 결과.
        // drain은 polling을 시간이 다 될 때까지 반복해 들어온 모든 메시지를 List로 반환한다.
        List<ConsumerRecord<String,String>> all = consumer.drain("order-events", Duration.ofSeconds(2));
        // 발행이 정확히 한 번만 일어났음을 확인 — 중복 발행 없음.
        assertThat(all).hasSize(1);
    }
}
