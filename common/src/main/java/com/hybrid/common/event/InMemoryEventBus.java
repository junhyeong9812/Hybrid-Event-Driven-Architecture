package com.hybrid.common.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryEventBus implements EventBus {

    private final EventStore store;
    private final Map<Class<? extends DomainEvent>, List<Consumer<DomainEvent>>> handlers
            = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    public InMemoryEventBus(EventStore store) { this.store = store; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                .add((Consumer<DomainEvent>) handler);
    }

    @Override
    public void publish(DomainEvent event) {
        store.append(event);
        for (Consumer<DomainEvent> h : handlers.getOrDefault(event.getClass(), List.of())) {
            try { h.accept(event); }
            catch (Exception e) { log.error("handler failed for {}", event, e); }
        }
    }
}