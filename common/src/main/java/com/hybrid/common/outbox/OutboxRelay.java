package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TOPIC = "order-events";
    private static final int BATCH = 100;
    private static final int MAX_RETRY = 10;

    private final OutboxRepository repo;
    private final KafkaTemplate<String,String> kafka;
    private final MeterRegistry meter;

    public OutboxRelay(OutboxRepository repo,
                       KafkaTemplate<String,String> kafka,
                       MeterRegistry meter) {
        this.repo = repo;
        this.kafka = kafka;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        // Phase 3: 멀티 인스턴스 안전성을 위해 행 잠금 기반 fetch.
        // FOR UPDATE SKIP LOCKED로 다른 노드가 잡은 행은 건너뜀 → 같은 행이 두 노드에서 동시 발행되지 않음.
        // @Transactional 종료(커밋/롤백) 시점에 행 잠금이 자동 해제됨.
        List<OutboxEvent> pending = repo.lockPending(BATCH);

        for (OutboxEvent e : pending) {
            try {
                ProducerRecord<String,String> record = new ProducerRecord<>(
                        TOPIC, e.getAggregateId(), e.getPayload());
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                record.headers().add("eventType", e.getEventType().getBytes());

                kafka.send(record).get(5, TimeUnit.SECONDS);
                e.markPublished();
                meter.counter("outbox.publish.success").increment();
            } catch (Exception ex) {
                e.incrementRetry();
                if (e.getRetryCount() >= MAX_RETRY) e.markDeadLetter();
                meter.counter("outbox.publish.failure").increment();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
    }
}