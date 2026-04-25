package com.hybrid.order.service;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.OrderConfirmed;
import com.hybrid.common.event.contract.OrderCreated;
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository repo;
    private final TransactionalEventPublisher publisher;

    public OrderService(OrderRepository repo, TransactionalEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
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
        publisher.publish(new OrderConfirmed(orderId));
    }

    @Transactional
    public Long createWithForcedRollback(CreateOrderCommand cmd) {
        Long id = create(cmd);
        throw new RuntimeException("forced rollback for test");
    }
}