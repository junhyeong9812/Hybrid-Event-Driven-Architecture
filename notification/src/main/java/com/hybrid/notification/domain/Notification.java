package com.hybrid.notification.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    public enum Channel { EMAIL, PUSH, SMS }
    public enum Status { PENDING, SENT, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private String type;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant sentAt;

    protected Notification() {}

    public static Notification create(Long orderId, String type, Channel channel) {
        Notification n = new Notification();
        n.orderId = orderId;
        n.type = type;
        n.channel = channel;
        n.status = Status.PENDING;
        return n;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed() { this.status = Status.FAILED; }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getType() { return type; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
}
