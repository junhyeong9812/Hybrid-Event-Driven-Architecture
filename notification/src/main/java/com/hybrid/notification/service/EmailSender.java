package com.hybrid.notification.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final EmailGateway gateway;
    private final DeferredEmailQueue deferred;

    public EmailSender(EmailGateway gateway, DeferredEmailQueue deferred) {
        this.gateway = gateway;
        this.deferred = deferred;
    }

    @CircuitBreaker(name = "email", fallbackMethod = "fallback")
    public void send(String to, String content) {
        gateway.deliver(to, content);   // 외부 SMTP/SaaS 호출 — 실패 가능성
    }

    void fallback(String to, String content, Throwable t) {
        log.warn("email circuit open, message deferred: {}", to, t);
        deferred.enqueue(new DeferredEmail(to, content));   // 회복 후 재발송 대상
    }
}