package com.hybrid.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private final OutboxRepository repo;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "outbox payload 직렬화 실패: type=" + eventType + ", id=" + aggregateId, e);
        }
        repo.save(OutboxEvent.of(aggregateType, aggregateId, eventType, json));
    }
}