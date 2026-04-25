package com.hybrid.common.event;

import java.util.function.Consumer;

public interface EventBus {
    <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler);
    void publish(DomainEvent event);
}