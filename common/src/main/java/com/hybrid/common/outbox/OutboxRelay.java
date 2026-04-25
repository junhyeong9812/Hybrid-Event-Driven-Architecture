package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private final OutboxRepository repo;
    private final KafkaTemplate<String,String> kafka;

    public OutboxRelay(OutboxRepository repo, KafkaTemplate<String,String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending =
                repo.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent e : pending) {
            try {
                ProducerRecord<String,String> record = new ProducerRecord<>(
                        TOPIC, e.getAggregateId(), e.getPayload());
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                record.headers().add("eventType", e.getEventType().getBytes());

                kafka.send(record).get(5, TimeUnit.SECONDS);
                e.markPublished();
            } catch (Exception ex) {
                e.incrementRetry();
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
            }
        }
    }
}