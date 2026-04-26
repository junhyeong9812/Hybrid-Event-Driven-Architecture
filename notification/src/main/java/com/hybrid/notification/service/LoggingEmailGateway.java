package com.hybrid.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영 기본 EmailGateway 구현 — Phase 3 학습 단계에선 로그만 남긴다.
 *
 * 실제 운영 환경에선 SMTP / SaaS(SendGrid 등) 어댑터로 교체된다.
 * @Profile("!test")로 테스트 환경에선 EmailSenderStub이 우선되도록 분기.
 */
@Component
@Profile("!test")
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);

    @Override
    public void deliver(String to, String content) {
        log.info("[email] to={} content={}", to, content);
        // 실제 발송 클라이언트 호출 자리.
    }
}
