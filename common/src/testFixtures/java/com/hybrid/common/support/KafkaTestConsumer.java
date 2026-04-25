package com.hybrid.common.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.DisposableBean;

/**
 * 테스트가 직접 Kafka 토픽을 구독해 발행 결과를 검증할 때 쓰는 헬퍼.
 *
 * 운영 코드의 @KafkaListener를 거치지 않고, 테스트 시점에만 raw consumer를 띄워
 * "정말로 Kafka에 메시지가 들어갔는가"를 확인한다.
 *
 * 토픽별로 독립 consumer를 캐시한다 — 같은 토픽을 여러 테스트가 polling해도
 * offset이 이어지지 않게 매번 새 group.id를 사용해야 한다면 reset()으로 정리.
 */
public class KafkaTestConsumer implements DisposableBean {

    private final String bootstrapServers;
    private final Map<String, KafkaConsumer<String, String>> consumers = new ConcurrentHashMap<>();

    public KafkaTestConsumer(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * 지정 토픽에서 한 건의 메시지를 받아온다.
     * @param timeout 메시지가 도착할 때까지 기다릴 최대 시간
     * @return 받은 메시지, 타임아웃이면 null
     */
    public ConsumerRecord<String, String> poll(String topic, Duration timeout) {
        KafkaConsumer<String, String> consumer = getOrCreate(topic);
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        if (records.isEmpty()) return null;
        return records.iterator().next();
    }

    /**
     * 지정 토픽에서 timeout 시간 동안 도착하는 모든 메시지를 끌어모은다.
     * @return 받은 메시지 리스트 (빈 리스트 가능)
     */
    public List<ConsumerRecord<String, String>> drain(String topic, Duration timeout) {
        KafkaConsumer<String, String> consumer = getOrCreate(topic);
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records =
                consumer.poll(Duration.ofMillis(200));
            records.forEach(all::add);
        }
        return all;
    }

    /** 모든 캐시된 consumer를 닫고 새로 시작 — 테스트 간 격리가 필요할 때. */
    public void reset() {
        consumers.values().forEach(KafkaConsumer::close);
        consumers.clear();
    }

    @Override
    public void destroy() { reset(); }

    private KafkaConsumer<String, String> getOrCreate(String topic) {
        return consumers.computeIfAbsent(topic, this::create);
    }

    private KafkaConsumer<String, String> create(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // 매번 다른 group.id — 테스트끼리 offset이 안 섞이게.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
