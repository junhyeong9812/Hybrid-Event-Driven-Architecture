package com.hybrid.notification.service;

/**
 * 외부 메일 시스템 호출 추상 — 헥사고날의 Port.
 *
 * 운영 구현: LoggingEmailGateway (현재 학습 단계, 로그만).
 * 테스트 구현: EmailSenderStub (실패 주입 가능).
 *
 * 이 추상 덕분에 EmailSender(Adapter)는 외부 시스템 종류에 무관해진다.
 * SMTP / SendGrid / SES 등으로 교체할 때 이 인터페이스 구현체만 갈아끼우면 됨.
 */
public interface EmailGateway {
    void deliver(String to, String content);
}
