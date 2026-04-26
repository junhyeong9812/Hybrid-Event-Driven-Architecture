package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ sweeper의 동작을 common 모듈 안에서 검증한다.
 * admin 엔드포인트(/admin/dlq/{id}/retry) HTTP 검증은 app/integration의 별도 테스트로 분리 —
 * common은 web 의존성을 갖지 않는다.
 */
class DeadLetterTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired DeadLetterRepository dlqRepo;
    @Autowired OutboxRelay relay;
    @Autowired DeadLetterSweeper deadLetterSweeper;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() {
        kafkaProducerStub.reset();
    }

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
