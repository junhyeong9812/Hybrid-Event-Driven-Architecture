package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka 발행이 실패하는 동안 outbox 상태가 보존되고, 복구 후 자동으로 발행이 따라잡는 시나리오를 검증한다.
 *
 * 실제 KafkaContainer를 stop/start하는 대신 KafkaProducerStub.alwaysFail / reset으로
 * 발행 실패를 결정적으로 시뮬레이션한다 — 컨테이너 재시작에 따른 bootstrap URL 변경 같은 비결정성을 제거.
 *
 * common 모듈 안에서 자족적으로 동작하도록 outbox 행을 직접 INSERT한다 (order 모듈 의존 없음).
 */
class KafkaFailureRecoveryTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() {
        kafkaProducerStub.reset();
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
