package com.hybrid.notification.kafka;

import com.hybrid.notification.inbox.InboxConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 메시지 → InboxConsumer 어댑터.
 *
 * 책임 분리:
 *   - 이 클래스: Kafka 프로토콜 (헤더 추출, 토픽 구독).
 *   - InboxConsumer: 비즈니스 로직 (멱등성 + 알림 처리).
 *
 * @KafkaListener:
 *   Spring Kafka가 별도 스레드에서 polling 루프를 돌리며 이 메서드 호출.
 *   group-id로 컨슈머 그룹 구분 — 같은 group-id의 인스턴스끼리 파티션 분산.
 */
@Component
public class NotificationKafkaListener {

    private final InboxConsumer inboxConsumer;

    public NotificationKafkaListener(InboxConsumer inboxConsumer) {
        this.inboxConsumer = inboxConsumer;
    }

    /**
     * Kafka 메시지 수신 콜백.
     * Spring Kafka가 이 메서드를 별도 컨슈머 스레드에서 호출.
     */
    @KafkaListener(topics = "order-events", groupId = "notification")
    public void onMessage(ConsumerRecord<String,String> record) {
        // 헤더에서 메타데이터 추출 — 페이로드 파싱 없이 라우팅 정보 획득.
        String messageId = header(record, "messageId");
        String eventType = header(record, "eventType");
        // 비즈니스 처리는 InboxConsumer에 위임 (멱등성 보장 포함).
        inboxConsumer.consume(messageId, eventType, record.value());
    }

    /**
     * Kafka 헤더에서 마지막 값 추출 — null 안전.
     * <?,?> wildcard로 ConsumerRecord의 key/value 타입 무관심 (헤더만 다루므로).
     */
    private String header(ConsumerRecord<?,?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }
}
