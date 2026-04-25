package com.hybrid.common.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

/**
 * 테스트 전용 KafkaTemplate 대체.
 *
 * 운영 KafkaTemplate을 그대로 상속하므로 평소엔 진짜 Kafka 컨테이너로 메시지를 보낸다.
 * 테스트에서 실패 시나리오를 만들고 싶을 때만 failNext / alwaysFail 호출.
 *
 * @TestConfiguration이 @Bean으로 노출하면 Spring Boot 자동 설정의
 * KafkaTemplate(@ConditionalOnMissingBean)을 대체한다.
 */
public class KafkaProducerStub extends KafkaTemplate<String, String> {

    /** 다음 send() 한 번만 실패시킬 예외. 사용 후 자동으로 비워짐. */
    private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();

    /** 모든 send()를 계속 실패시키는 플래그. reset()까지 유지. */
    private final AtomicBoolean alwaysFail = new AtomicBoolean(false);

    public KafkaProducerStub(ProducerFactory<String, String> producerFactory) {
        super(producerFactory);
    }

    /** 다음 send()만 주어진 예외로 실패시킨다. */
    public void failNext(Throwable t) { nextFailure.set(t); }

    /** 이후 모든 send()가 실패한다. reset() 호출 전까지. */
    public void alwaysFail() { alwaysFail.set(true); }

    /** 테스트 사이 정리 — 실패 설정을 모두 해제. */
    public void reset() {
        nextFailure.set(null);
        alwaysFail.set(false);
    }

    @Override
    public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
        if (alwaysFail.get()) {
            return CompletableFuture.failedFuture(
                new RuntimeException("kafka producer stub: alwaysFail"));
        }
        Throwable next = nextFailure.getAndSet(null);
        if (next != null) {
            return CompletableFuture.failedFuture(next);
        }
        return super.send(record);   // 실패 설정 없으면 진짜 Kafka로 발행
    }
}
