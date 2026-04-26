package com.hybrid.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hybrid.notification.domain.Notification;
import com.hybrid.notification.domain.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 처리 유스케이스 — 여러 채널을 조립.
 *
 * 흐름:
 *   1. payload JSON에서 orderId 추출.
 *   2. EMAIL 채널 — Notification 행 저장 + EmailSender 호출 + markSent.
 *   3. PUSH 채널 — 같은 패턴.
 *
 * 같은 트랜잭션:
 *   둘 중 하나 실패 시 둘 다 롤백 → InboxConsumer 측에서도 inbox INSERT 롤백 → 재처리 가능.
 *
 * 예외 정책:
 *   기술 예외(JSON 파싱 등)는 IllegalStateException으로 wrap → 호출자(InboxConsumer)에서 transaction 롤백 트리거.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    // EmailSender는 @CircuitBreaker로 감싸진 외부 발송 — 실패 시 fallback으로 deferred queue.
    private final EmailSender emailSender;
    // PushSender는 단순 mock — 학습 단계에선 로그만 남김.
    private final PushSender pushSender;
    // 페이로드 JSON 파싱용 — Spring Boot가 자동 구성한 ObjectMapper 주입.
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

    /**
     * 메시지 한 건 처리 — InboxConsumer가 호출.
     * 트랜잭션 안에서 두 채널의 Notification 저장 + 외부 발송.
     */
    @Transactional
    public void process(String eventType, String payload) {
        try {
            // payload JSON에서 orderId 추출.
            //   타입 안전한 record 매핑 대신 JsonNode 사용 — 다른 이벤트 타입에 같은 핸들러가 대응 가능.
            JsonNode node = objectMapper.readTree(payload);
            Long orderId = node.get("orderId").asLong();

            // EMAIL 채널 처리.
            //   1) PENDING 상태로 INSERT (실패 시 추적용).
            //   2) 외부 발송 — Circuit Breaker가 감쌈.
            //   3) 성공 시 markSent — JPA dirty checking으로 트랜잭션 커밋 시 UPDATE.
            Notification email = repo.save(Notification.create(orderId, eventType, Notification.Channel.EMAIL));
            emailSender.send("user-" + orderId + "@example.com", "Order " + orderId + " " + eventType);
            email.markSent();

            // PUSH 채널 처리 — 같은 패턴.
            Notification push = repo.save(Notification.create(orderId, eventType, Notification.Channel.PUSH));
            pushSender.send(orderId, eventType);
            push.markSent();

        } catch (Exception e) {
            // 실패 사유 로깅 + RuntimeException으로 wrap.
            // → InboxConsumer 트랜잭션 롤백 → inbox INSERT도 폐기 → 다음 재수신 때 재처리.
            log.error("notification process failed: {}", payload, e);
            throw new IllegalStateException("notification failed", e);
        }
    }
}
