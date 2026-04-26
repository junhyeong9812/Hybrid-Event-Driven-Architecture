package com.hybrid.notification.inbox;

import com.hybrid.notification.service.NotificationService;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// DB UNIQUE 제약 위반 시 Spring이 던지는 예외.
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox 패턴의 핵심 — 메시지 멱등성(idempotency) 처리.
 *
 * 풀어주는 함정 — at-least-once 메시징의 중복:
 *   Kafka가 같은 메시지를 두 번 이상 도착시킬 수 있음.
 *   같은 messageId를 두 번 처리하면 알림이 두 번 발송 → 사용자 경험 망가짐.
 *
 * 두 단계 방어:
 *   1. 애플리케이션 레벨 — existsByMessageId로 사전 체크.
 *   2. DB UNIQUE 인덱스 — 동시 요청이 사전 체크를 모두 통과한 경쟁 상태에서
 *      INSERT 시점에 DataIntegrityViolationException 발생 → 한 행만 살아남음.
 *
 * effectively-exactly-once: at-least-once + 멱등성 = 결과적으로 한 번만 처리된 것과 동일.
 */
@Component
public class InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    private final InboxRepository inbox;
    private final NotificationService notifications;
    // 메트릭으로 멱등성의 효과를 수치화 — duplicate가 0에 가까우면 Kafka 안정.
    private final MeterRegistry meter;

    public InboxConsumer(InboxRepository inbox,
                         NotificationService notifications,
                         MeterRegistry meter) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.meter = meter;
    }

    /**
     * 메시지 한 건 처리 — Kafka 리스너가 호출.
     *
     * @Transactional 안에서:
     *   - 1차 멱등성 체크 (existsByMessageId)
     *   - inbox INSERT + 비즈니스 로직 (NotificationService.process)
     *   - 둘 중 하나라도 예외 → 함께 롤백 → 다음 재수신 때 다시 처리.
     */
    @Transactional
    public void consume(String messageId, String eventType, String payload) {
        // 1차 방어선 — 이미 처리된 메시지면 스킵.
        if (inbox.existsByMessageId(messageId)) {
            meter.counter("inbox.duplicate.count").increment();
            log.info("duplicate skipped: {}", messageId);
            return;
        }
        try {
            // inbox 행 INSERT — UNIQUE 인덱스가 동시 중복을 막음.
            inbox.save(InboxEvent.of(messageId, eventType, payload));
            // 비즈니스 처리 — 같은 트랜잭션이라 실패 시 inbox INSERT도 롤백.
            notifications.process(eventType, payload);
            meter.counter("inbox.processed.count").increment();
        } catch (DataIntegrityViolationException dup) {
            // 2차 방어선 — UNIQUE 위반 = 동시 다른 컨슈머가 먼저 처리한 케이스.
            // 예외를 RuntimeException으로 다시 던지지 않고 "중복 스킵"으로 번역.
            // (다시 던지면 Kafka가 재시도해서 무한 루프 가능)
            meter.counter("inbox.duplicate.count").increment();
            log.info("concurrent duplicate skipped: {}", messageId);
        }
    }
}
