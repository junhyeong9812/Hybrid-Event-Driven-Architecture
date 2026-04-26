package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxStatus;
import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaProducerStub;
import com.hybrid.notification.domain.NotificationRepository;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ChaosScenarioTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OutboxRepository outboxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired KafkaProducerStub kafkaProducerStub;

    @AfterEach
    void resetStub() {
        kafkaProducerStub.reset();
    }

    @Test
    void 주문_100건_처리_중_발행이_5초간_막혔다_풀려도_모든_알림이_발송된다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Long id = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));
            orderService.confirm(id);
            ids.add(id);
        }

        kafkaProducerStub.alwaysFail();
        Thread.sleep(5_000);
        kafkaProducerStub.reset();

        await().atMost(60, SECONDS).untilAsserted(() -> {
            assertThat(outboxRepo.countByStatus(OutboxStatus.PENDING)).isZero();
            assertThat(outboxRepo.countByStatus(OutboxStatus.DEAD_LETTER)).isZero();
            for (Long id : ids)
                assertThat(notificationRepo.findByOrderId(id)).isPresent();
        });
    }
}
