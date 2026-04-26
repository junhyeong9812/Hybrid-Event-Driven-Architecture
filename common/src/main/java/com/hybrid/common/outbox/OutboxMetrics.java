package com.hybrid.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repo) {
        Gauge.builder("outbox.pending.count",
                        () -> repo.countByStatus(OutboxStatus.PENDING))
                .register(registry);
        Gauge.builder("outbox.deadletter.count",
                        () -> repo.countByStatus(OutboxStatus.DEAD_LETTER))
                .register(registry);
    }
}