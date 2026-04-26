package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.Map;

import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.notification.inbox.InboxRepository;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class Phase2E2ETest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired InboxRepository inboxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired KafkaTestProducer producer;

    @Test
    void 주문_확정부터_알림_발송까지_한_번에_흐른다() {
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.valueOf(1000)));

        await().atMost(15, SECONDS).untilAsserted(() -> {
            // Phase 1 결과
            assertThat(orderRepo.findById(orderId).orElseThrow().status())
                    .isEqualTo(OrderStatus.CONFIRMED);

            // Outbox → Kafka → Inbox → Notification
            assertThat(outboxRepo.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
            assertThat(inboxRepo.count()).isEqualTo(1);
            assertThat(notificationRepo.findByOrderId(orderId)).isPresent();
        });
    }

    @Test
    void 동일_Kafka_메시지가_두_번_수신되어도_알림은_한_번만_발송된다() {
        producer.send("order-events", "42",
                "{\"orderId\":42}", Map.of("messageId","dup-1","eventType","OrderConfirmed"));
        producer.send("order-events", "42",
                "{\"orderId\":42}", Map.of("messageId","dup-1","eventType","OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(inboxRepo.count()).isEqualTo(1);
            assertThat(notificationRepo.findAllByOrderId(42L)).hasSize(1);
        });
    }
}