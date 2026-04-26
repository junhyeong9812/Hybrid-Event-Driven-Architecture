package com.hybrid.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hybrid.common.event.DomainEvent;
import com.hybrid.common.event.EventStore;
import com.hybrid.common.event.InMemoryEventBus;
import com.hybrid.common.event.InMemoryEventStore;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.common.event.contract.PaymentCompleted;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventBusTest {

    @Test
    void subscribe한_핸들러는_publish시_호출된다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        List<DomainEvent> received = new ArrayList<>();
        bus.subscribe(OrderCreated.class, received::add);

        OrderCreated event = new OrderCreated(1L, BigDecimal.valueOf(1000));
        bus.publish(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void 다른_타입_이벤트는_해당_타입_핸들러만_호출한다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        List<DomainEvent> orderHandler = new ArrayList<>();
        List<DomainEvent> paymentHandler = new ArrayList<>();
        bus.subscribe(OrderCreated.class, orderHandler::add);
        bus.subscribe(PaymentCompleted.class, paymentHandler::add);

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(orderHandler).hasSize(1);
        assertThat(paymentHandler).isEmpty();
    }

    @Test
    void publish한_이벤트는_EventStore에도_append된다() {
        EventStore store = new InMemoryEventStore();
        InMemoryEventBus bus = new InMemoryEventBus(store);

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(store.latestOffset()).isEqualTo(1L);
    }

    @Test
    void 한_핸들러의_예외가_다른_핸들러를_중단시키지_않는다() {
        InMemoryEventBus bus = new InMemoryEventBus(new InMemoryEventStore());
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        bus.subscribe(OrderCreated.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(OrderCreated.class, e -> secondCalled.set(true));

        bus.publish(new OrderCreated(1L, BigDecimal.TEN));

        assertThat(secondCalled).isTrue();
    }
}
