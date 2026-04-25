package com.hybrid.common.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    default UUID eventId() { return UUID.randomUUID(); }      // 호출 시마다 다름 방지 필요 → Refactor 단계로
    default Instant occurredAt() { return Instant.now(); }
    String eventType();
    String aggregateId();
}