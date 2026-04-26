package com.hybrid.common.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;

// Micrometer로 발행 성공/실패 메트릭 노출.
import io.micrometer.core.instrument.MeterRegistry;
// Kafka 메시지 표현.
import org.apache.kafka.clients.producer.ProducerRecord;
// 실패 로그.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Spring Kafka 고수준 API.
import org.springframework.kafka.core.KafkaTemplate;
// 주기적 실행.
import org.springframework.scheduling.annotation.Scheduled;
// Spring 빈 등록.
import org.springframework.stereotype.Component;
// fetch + 상태 갱신을 한 트랜잭션으로.
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox Relay — outbox 테이블의 PENDING 행을 폴링해 Kafka로 발행.
 *
 * 핵심 사상:
 *   "메시지 큐(Kafka)와 DB는 별개 시스템이라 원자적 쓰기가 불가능하다"는 함정을
 *   "DB 트랜잭션이 진실의 원천이고, Relay가 비동기로 Kafka까지 따라간다"로 회피.
 *   → at-least-once 발행 보장.
 *
 * 동시성:
 *   FOR UPDATE SKIP LOCKED로 멀티 인스턴스 안전 (Phase 3).
 *
 * 실패 처리:
 *   각 발행마다 try/catch — 한 건 실패가 배치 전체를 막지 않음.
 *   retryCount 누적 → MAX_RETRY 초과 시 DEAD_LETTER로 격리.
 */
@Component
public class OutboxRelay {

    // 발행 실패 로그 — 운영 시 ELK/Loki에서 추적.
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    // 발행 대상 Kafka 토픽 — 모든 도메인의 outbox 메시지가 이 한 토픽으로.
    //   미래에 도메인별 토픽 분리가 필요하면 aggregateType으로 라우팅 추가.
    private static final String TOPIC = "order-events";
    // 한 번의 폴링에서 가져올 최대 행 수.
    //   너무 크면 트랜잭션이 길어져 잠금 보유 시간 ↑ → 100이 균형점.
    private static final int BATCH = 100;
    // 발행 실패 누적 한계 — 초과 시 DEAD_LETTER로 격리.
    //   외부 시스템 일시 장애 vs 영구 실패를 시간으로 구분: 10회 ≈ 약 50초의 재시도 기회.
    private static final int MAX_RETRY = 10;

    // outbox 테이블 액세스.
    private final OutboxRepository repo;
    // Kafka producer — 테스트 환경에선 KafkaProducerStub으로 대체됨.
    private final KafkaTemplate<String,String> kafka;
    // 발행 성공/실패 카운터 등록.
    private final MeterRegistry meter;

    // Spring 생성자 주입 — 모든 의존을 final로 받아 불변성 확보.
    public OutboxRelay(OutboxRepository repo,
                       KafkaTemplate<String,String> kafka,
                       MeterRegistry meter) {
        this.repo = repo;
        this.kafka = kafka;
        this.meter = meter;
    }

    /**
     * 1초마다 (이전 poll 종료 후) 한 번 실행 — fixedRate 아닌 fixedDelay로 백압 자연 발생.
     * 메서드 전체가 하나의 트랜잭션 — fetch부터 markPublished까지 원자적.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        // 행 잠금으로 PENDING fetch — 다른 Relay 인스턴스와 같은 행을 안 잡음.
        // 트랜잭션 종료 시 잠금 자동 해제.
        List<OutboxEvent> pending = repo.lockPending(BATCH);

        // 한 건씩 직렬 발행 — 한 실패가 배치 전체를 막지 않게 try/catch 개별화.
        for (OutboxEvent e : pending) {
            try {
                // Kafka 메시지 빌드 — key는 aggregateId(같은 주문은 같은 파티션).
                ProducerRecord<String,String> record = new ProducerRecord<>(
                        TOPIC, e.getAggregateId(), e.getPayload());
                // 헤더로 메타데이터 분리 — 컨슈머가 페이로드 파싱 없이 라우팅 가능.
                //   messageId: outbox.id 그대로 → Inbox 멱등성 키로 사용.
                record.headers().add("messageId", String.valueOf(e.getId()).getBytes());
                //   eventType: 도메인 이벤트 이름 — 컨슈머가 처리 분기에 사용.
                record.headers().add("eventType", e.getEventType().getBytes());

                // 동기 send + 5초 ack 대기.
                //   비동기로 받아 throughput 올리는 것도 가능하지만,
                //   현재는 단순성을 위해 직렬 + 동기 + 같은 트랜잭션에서 상태 갱신.
                kafka.send(record).get(5, TimeUnit.SECONDS);

                // 도메인 객체에 상태 변경 — JPA dirty checking으로 트랜잭션 커밋 시 UPDATE.
                e.markPublished();
                // 운영 메트릭 — 처리량 추적.
                meter.counter("outbox.publish.success").increment();

            } catch (Exception ex) {
                // 발행 실패 — Kafka 다운, 타임아웃, 네트워크 에러 등 모두 여기로.
                e.incrementRetry();
                // 한계 초과 시 DEAD_LETTER로 격리 — Sweeper가 outbox_dlq로 이관할 대상.
                if (e.getRetryCount() >= MAX_RETRY) e.markDeadLetter();
                // 메트릭으로 에러율 노출.
                meter.counter("outbox.publish.failure").increment();
                // 운영자가 추적할 수 있게 로그.
                log.warn("relay failed for outbox id={}, retry={}", e.getId(), e.getRetryCount(), ex);
                // 예외를 다시 던지지 않음 — 다음 행도 시도해야 하므로.
            }
        }
        // 트랜잭션 종료 → markPublished/incrementRetry 결과 커밋 + 모든 잠금 해제.
    }
}
