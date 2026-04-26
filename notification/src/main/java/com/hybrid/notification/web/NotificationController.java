package com.hybrid.notification.web;

import java.util.List;

import com.hybrid.notification.domain.Notification;
import com.hybrid.notification.domain.NotificationRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 도메인 HTTP 진입점 — 발송된 알림 조회.
 *
 * 알림 생성은 외부에서 직접 호출되지 않음 — Kafka 메시지가 트리거.
 * 따라서 GET 엔드포인트만 노출.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    /** 주문ID로 모든 채널의 알림 조회 (EMAIL + PUSH 등). */
    @GetMapping("/{orderId}")
    public List<Notification> get(@PathVariable Long orderId) {
        return repo.findAllByOrderId(orderId);
    }
}
