package com.hybrid.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hybrid.notification.domain.Notification;
import com.hybrid.notification.domain.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final EmailSender emailSender;
    private final PushSender pushSender;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repo,
                               EmailSender emailSender,
                               PushSender pushSender,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.emailSender = emailSender;
        this.pushSender = pushSender;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(String eventType, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            Long orderId = node.get("orderId").asLong();

            Notification email = repo.save(Notification.create(orderId, eventType, Notification.Channel.EMAIL));
            emailSender.send("user-" + orderId + "@example.com", "Order " + orderId + " " + eventType);
            email.markSent();

            Notification push = repo.save(Notification.create(orderId, eventType, Notification.Channel.PUSH));
            pushSender.send(orderId, eventType);
            push.markSent();
        } catch (Exception e) {
            log.error("notification process failed: {}", payload, e);
            throw new IllegalStateException("notification failed", e);
        }
    }
}
