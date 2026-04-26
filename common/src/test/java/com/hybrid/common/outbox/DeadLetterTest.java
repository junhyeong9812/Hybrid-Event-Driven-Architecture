package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired DeadLetterRepository dlqRepo;
    @Autowired OutboxRelay relay;
    @Autowired DeadLetterSweeper deadLetterSweeper;
    @Autowired KafkaProducerStub kafkaProducerStub;
    @Autowired WebTestClient webTestClient;

    @Test
    void DEAD_LETTER_상태_이벤트는_dlq_테이블로_이동한다() {
        OutboxEvent e = outboxRepo.save(OutboxEvent.of("Order","42","OrderConfirmed","{}"));
        kafkaProducerStub.alwaysFail();

        for (int i = 0; i < 11; i++) relay.poll();
        deadLetterSweeper.run();   // 주기 작업 직접 호출

        assertThat(outboxRepo.findById(e.getId())).isEmpty();
        assertThat(dlqRepo.findByOriginalOutboxId(e.getId())).isPresent();
    }

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