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
