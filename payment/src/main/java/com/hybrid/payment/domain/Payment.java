package com.hybrid.payment.domain;

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
@Table(name = "payments")
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private Instant createdAt;

    protected Payment() {}

    public static Payment request(Long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive");
        Payment p = new Payment();
        p.orderId = orderId;
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        return p;
    }

    public void complete() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot complete from " + status);
        status = PaymentStatus.COMPLETED;
    }

    public void fail() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("cannot fail from " + status);
        status = PaymentStatus.FAILED;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus status() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}