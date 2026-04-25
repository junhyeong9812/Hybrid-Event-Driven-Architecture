package com.hybrid.common.event;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventStoreTest {

    /** EventStore 단위 테스트 전용 더미 이벤트. 도메인 이벤트와 분리해 모듈 경계를 침해하지 않는다. */
    record OrderCreatedTestEvent(String aggregateId) implements DomainEvent {
        @Override public String eventType() { return "OrderCreatedTest"; }
    }

    @Test
    void append한_이벤트는_read로_조회된다() {
        EventStore store = new InMemoryEventStore();
        DomainEvent e = new OrderCreatedTestEvent("order-1");

        long offset = store.append(e);
        List<DomainEvent> read = store.read(offset, 1);

        assertThat(read).containsExactly(e);
    }

    @Test
    void offset은_단조증가한다() {
        EventStore store = new InMemoryEventStore();
        long o1 = store.append(new OrderCreatedTestEvent("o1"));
        long o2 = store.append(new OrderCreatedTestEvent("o2"));
        long o3 = store.append(new OrderCreatedTestEvent("o3"));

        assertThat(o1).isLessThan(o2);
        assertThat(o2).isLessThan(o3);
    }

    @Test
    void read는_지정한_offset부터_count만큼_반환한다() {
        EventStore store = new InMemoryEventStore();
        long first = store.append(new OrderCreatedTestEvent("o1"));
        store.append(new OrderCreatedTestEvent("o2"));
        store.append(new OrderCreatedTestEvent("o3"));

        List<DomainEvent> read = store.read(first + 1, 2);

        assertThat(read).hasSize(2);
        assertThat(read.get(0).aggregateId()).isEqualTo("o2");
        assertThat(read.get(1).aggregateId()).isEqualTo("o3");
    }

    @Test
    void 동시에_append해도_offset이_중복되지_않는다() throws Exception {
        EventStore store = new InMemoryEventStore();
        int threads = 16, perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Long> offsets = ConcurrentHashMap.newKeySet();

        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++)
                    offsets.add(store.append(new OrderCreatedTestEvent("x")));
                done.countDown();
            });
        }
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(offsets).hasSize(threads * perThread);
    }
}