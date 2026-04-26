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