package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    public void send(Long orderId, String eventType) {
        log.info("[push] orderId={} eventType={}", orderId, eventType);
    }
}
