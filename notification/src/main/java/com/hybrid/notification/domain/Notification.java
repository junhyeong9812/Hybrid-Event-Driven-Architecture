package com.hybrid.notification.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Notification 애그리거트 — 발송된 알림의 영속 모델.
 *
 * 한 비즈니스 사건이 여러 채널로 발송될 수 있음 (EMAIL + PUSH + SMS).
 * 각 채널마다 별도 Notification 행 — orderId가 같지만 channel로 구분.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    /** 발송 채널 — 미래에 SMS, Webhook 등 추가 가능. */
    public enum Channel { EMAIL, PUSH, SMS }
    /** 발송 결과 상태. */
    public enum Status { PENDING, SENT, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    // 이벤트 타입 (예: "OrderConfirmed") — 운영 분석용.
    private String type;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Status status;
    // 실제 발송 완료 시각 — 지연 분석에 사용.
    private Instant sentAt;

    protected Notification() {}

    /** 정적 팩토리 — 발송 시도 직전에 PENDING 상태로 생성. */
    public static Notification create(Long orderId, String type, Channel channel) {
        Notification n = new Notification();
        n.orderId = orderId;
        n.type = type;
        n.channel = channel;
        n.status = Status.PENDING;
        return n;
    }

    /** 발송 성공 시 호출 — 시각 기록 + 상태 전이. */
    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    /** 발송 실패 시 호출 — Circuit Breaker fallback에서. */
    public void markFailed() { this.status = Status.FAILED; }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getType() { return type; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
}
