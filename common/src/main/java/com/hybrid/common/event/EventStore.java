package com.hybrid.common.event;

import java.util.List;

public interface EventStore {
    long append(DomainEvent event);
    List<DomainEvent> read(long from, int count);
    long latestOffset();
}