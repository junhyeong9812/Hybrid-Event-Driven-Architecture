package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 푸시 알림 발송기 — Phase 3 학습 단계에선 mock(로그만).
 *
 * 운영 도입 시:
 *   - EmailGateway 패턴을 따라 PushGateway 인터페이스로 추상화 후 FCM/APNs 어댑터.
 *   - Circuit Breaker 두르기 (외부 시스템이라 동일 위험).
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    /** mock 발송 — 실제 푸시 클라이언트 호출 자리. */
    public void send(Long orderId, String eventType) {
        log.info("[push] orderId={} eventType={}", orderId, eventType);
    }
}
