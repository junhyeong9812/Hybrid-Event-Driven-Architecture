package com.hybrid.common.outbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.notification.inbox.InboxConsumer;

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
    @Autowired InboxConsumer inboxConsumer;

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

    @Test
    void inbox_duplicate_count_카운터가_중복_수신_시_증가한다() {
        inboxConsumer.consume("msg-1","OrderConfirmed","{}");
        inboxConsumer.consume("msg-1","OrderConfirmed","{}");

        assertThat(registry.counter("inbox.duplicate.count").count())
                .isEqualTo(1.0);
    }
}