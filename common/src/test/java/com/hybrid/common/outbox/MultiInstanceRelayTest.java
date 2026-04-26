package com.hybrid.common.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.common.support.KafkaTestConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MultiInstanceRelayTest extends KafkaIntegrationTestBase {

    @Autowired OutboxRepository outboxRepo;
    @Autowired KafkaTestConsumer consumer;

    @Test
    void 두_개의_Relay가_동시에_poll해도_같은_이벤트를_두_번_발행하지_않는다() throws Exception {
        for (int i = 0; i < 50; i++)
            outboxRepo.save(OutboxEvent.of("Order", String.valueOf(i), "OrderConfirmed", "{}"));

        OutboxRelay r1 = newRelayInstance();
        OutboxRelay r2 = newRelayInstance();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(() -> { go.await(); r1.poll(); return null; });
        Future<?> f2 = pool.submit(() -> { go.await(); r2.poll(); return null; });

        go.countDown();
        f1.get(); f2.get();

        List<ConsumerRecord<String,String>> sent = consumer.drain("order-events", Duration.ofSeconds(5));
        Set<String> messageIds = sent.stream()
                .map(r -> new String(r.headers().lastHeader("messageId").value()))
                .collect(Collectors.toSet());
        assertThat(messageIds).hasSize(sent.size());    // 중복 없음
    }

    @Autowired OutboxRepository outboxRepository;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String,String> kafkaTemplate;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * 같은 Repository / KafkaTemplate / MeterRegistry를 공유하지만
     * 별도 OutboxRelay 인스턴스를 만든다.
     * Spring 컨테이너의 단일 빈을 두 번 받는 게 아니라,
     * "두 노드가 같은 DB·Kafka에 동시에 붙은 상황"을 시뮬레이션한다.
     */
    private OutboxRelay newRelayInstance() {
        return new OutboxRelay(outboxRepository, kafkaTemplate, meterRegistry);
    }
}