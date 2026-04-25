package com.hybrid.common.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TransactionalEventPublisher {

    private final InMemoryEventBus bus;

    public TransactionalEventPublisher(InMemoryEventBus bus) { this.bus = bus; }

    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bus.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { bus.publish(event); }
                }
        );
    }
}