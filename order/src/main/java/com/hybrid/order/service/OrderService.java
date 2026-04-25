package com.hybrid.order.service;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxWriter;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.common.event.contract.OrderConfirmed;
import com.hybrid.common.event.contract.OrderConfirmedPayload;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository repo;
    private final OutboxRepository outboxRepository;
    private final TransactionalEventPublisher publisher;
    private final OutboxWriter outboxWriter;

    public OrderService(OrderRepository repo,
                        OutboxRepository outboxRepository,
                        TransactionalEventPublisher publisher,
                        OutboxWriter outboxWriter) {
        this.repo = repo;
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public Long create(CreateOrderCommand cmd) {
        Order order = Order.create(cmd.customerId(), cmd.amount());
        repo.save(order);
        publisher.publish(new OrderCreated(order.getId(), order.getAmount()));
        return order.getId();
    }

    @Transactional
    public void confirm(Long orderId) {
        Order o = repo.findById(orderId).orElseThrow();
        o.confirm();

        outboxWriter.write("Order", orderId.toString(), "OrderConfirmed",
                new OrderConfirmedPayload(orderId, o.getAmount()));

        publisher.publish(new OrderConfirmed(orderId));
    }

    @Transactional
    public Long createWithForcedRollback(CreateOrderCommand cmd) {
        Long id = create(cmd);
        throw new RuntimeException("forced rollback for test");
    }

    @Transactional
    public void confirmWithForcedRollback(Long orderId) {
        confirm(orderId);
        throw new RuntimeException("forced rollback for test");
    }
}