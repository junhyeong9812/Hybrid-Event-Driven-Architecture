package com.hybrid.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long customerId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private Instant createdAt;

    protected Order() {}

    public static Order create(Long customerId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Order o = new Order();
        o.customerId = customerId;
        o.amount = amount;
        o.status = OrderStatus.CREATED;
        o.createdAt = Instant.now();
        return o;
    }

    public void confirm() {
        if (status != OrderStatus.CREATED)
            throw new IllegalStateException("cannot confirm from " + status);
        status = OrderStatus.CONFIRMED;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public OrderStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}