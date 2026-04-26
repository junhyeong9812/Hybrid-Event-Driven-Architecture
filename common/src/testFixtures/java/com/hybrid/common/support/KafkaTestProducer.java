package com.hybrid.common.support;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;

/**
 * 테스트가 직접 Kafka 토픽으로 메시지를 발행할 때 쓰는 헬퍼.
 *
 * 운영 코드의 OutboxRelay 경로를 거치지 않고, 외부에서 들어오는 메시지를 시뮬레이션할 때 사용.
 * 예: NotificationKafkaListener가 진짜 메시지를 받았을 때 어떻게 동작하는지 검증.
 *
 * 매번 send()는 동기로 ack까지 기다린다 — 테스트 결정성 확보.
 */
public class KafkaTestProducer implements DisposableBean {

    private final KafkaProducer<String, String> producer;

    public KafkaTestProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 테스트는 강한 ack 보장이 필요 — 데이터가 파티션 리더에 안전히 쓰이는지 확인.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(props);
    }

    /** key·value만 보내는 단순 형태. */
    public void send(String topic, String key, String value) {
        send(topic, key, value, Map.of());
    }

    /** 헤더까지 명시. messageId / eventType 같은 메타데이터 주입용. */
    public void send(String topic, String key, String value, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((k, v) ->
            record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(record).get();   // ack까지 동기 대기
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while sending", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("send failed for topic " + topic, e.getCause());
        }
    }

    @Override
    public void destroy() {
        producer.close();
    }
}
