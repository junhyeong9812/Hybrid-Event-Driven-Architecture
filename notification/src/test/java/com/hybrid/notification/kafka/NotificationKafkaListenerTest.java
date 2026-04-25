package com.hybrid.notification.kafka;

import java.util.Map;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.notification.inbox.InboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationKafkaListenerTest extends KafkaIntegrationTestBase {

    @Autowired KafkaTestProducer producer;
    @Autowired InboxRepository inboxRepository;
    @Autowired NotificationRepository notificationRepository;

    @Test
    void Kafka_메시지를_수신하면_InboxConsumer가_호출된다() {
        producer.send("order-events", "42",
                "{\"orderId\":42,\"amount\":1000}",
                Map.of("messageId", "outbox-1", "eventType", "OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(inboxRepository.existsByMessageId("outbox-1")).isTrue();
            assertThat(notificationRepository.findByOrderId(42L)).isPresent();
        });
    }
}