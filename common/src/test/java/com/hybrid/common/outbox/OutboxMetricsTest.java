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

/**
 * common 모듈에서 다루는 outbox 메트릭만 검증한다.
 * inbox.duplicate.count는 notification 모듈의 InboxConsumer가 발생시키므로
 * 이 모듈에선 검증할 수 없음 — InboxConsumerMetricsTest(notification/test)로 분리.
 */
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
