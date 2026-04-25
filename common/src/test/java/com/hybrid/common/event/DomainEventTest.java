package com.hybrid.common.event;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventTest {

    @Test
    void 이벤트는_고유한_eventId를_가진다() {
        DomainEvent e1 = new TestEvent("agg-1");
        DomainEvent e2 = new TestEvent("agg-1");

        assertThat(e1.eventId()).isNotNull();
        assertThat(e1.eventId()).isNotEqualTo(e2.eventId());
    }

    @Test
    void 이벤트는_생성_시점을_기록한다() {
        Instant before = Instant.now();
        DomainEvent e = new TestEvent("agg-1");
        Instant after = Instant.now();

        assertThat(e.occurredAt()).isBetween(before, after);
    }

    @Test
    void 이벤트는_aggregateId를_노출한다() {
        DomainEvent e = new TestEvent("agg-42");
        assertThat(e.aggregateId()).isEqualTo("agg-42");
    }

    record TestEvent(String aggregateId) implements DomainEvent {
        @Override public String eventType() { return "TestEvent"; }
    }
}