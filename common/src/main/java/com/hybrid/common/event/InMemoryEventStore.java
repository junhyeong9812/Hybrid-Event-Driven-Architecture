package com.hybrid.common.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryEventStore implements EventStore {

    private final List<DomainEvent> log = new CopyOnWriteArrayList<>();
    private final AtomicLong offset = new AtomicLong(0);

    @Override
    public synchronized long append(DomainEvent event) {
        long current = offset.getAndIncrement();
        log.add(event);
        return current;
    }

    @Override
    public List<DomainEvent> read(long from, int count) {
        int end = (int) Math.min(from + count, log.size());
        if (from >= log.size()) return List.of();
        return List.copyOf(log.subList((int) from, end));
    }

    @Override
    public long latestOffset() { return offset.get(); }
}