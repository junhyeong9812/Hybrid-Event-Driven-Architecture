package com.hybrid.common.outbox;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeadLetterSweeper {

    private final OutboxRepository outboxRepo;
    private final DeadLetterRepository dlqRepo;
    private final MeterRegistry meter;

    public DeadLetterSweeper(OutboxRepository outboxRepo,
                             DeadLetterRepository dlqRepo,
                             MeterRegistry meter) {
        this.outboxRepo = outboxRepo;
        this.dlqRepo = dlqRepo;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void run() {
        List<OutboxEvent> dead = outboxRepo.findByStatus(OutboxStatus.DEAD_LETTER);
        for (OutboxEvent e : dead) {
            dlqRepo.save(DeadLetterEvent.from(e));
            outboxRepo.delete(e);
            meter.counter("outbox.deadletter.moved").increment();
        }
    }
}