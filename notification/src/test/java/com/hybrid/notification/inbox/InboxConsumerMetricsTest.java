package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification 모듈의 InboxConsumer가 발생시키는 메트릭을 검증한다.
 * outbox 메트릭은 common 모듈 자체에서 OutboxMetricsTest가 검증.
 */
class InboxConsumerMetricsTest extends KafkaIntegrationTestBase {

    @Autowired MeterRegistry registry;
    @Autowired InboxConsumer inboxConsumer;

    @Test
    void inbox_duplicate_count_카운터가_중복_수신_시_증가한다() {
        inboxConsumer.consume("msg-1","OrderConfirmed","{\"orderId\":1}");
        inboxConsumer.consume("msg-1","OrderConfirmed","{\"orderId\":1}");   // 중복

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
