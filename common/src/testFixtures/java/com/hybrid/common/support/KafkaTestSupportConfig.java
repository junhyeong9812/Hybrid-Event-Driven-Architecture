package com.hybrid.common.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 테스트 헬퍼 빈 등록.
 *
 * - KafkaProducerStub: 운영 KafkaTemplate을 대체. 평소엔 진짜 컨테이너로 송신,
 *   테스트가 failNext/alwaysFail 호출 시 실패 시뮬레이션.
 * - KafkaTestConsumer: 테스트가 직접 토픽을 구독해 메시지를 검증.
 *
 * KafkaIntegrationTestBase가 @Import(KafkaTestSupportConfig.class)로 끌어들여
 * 모든 통합 테스트에서 자동 사용 가능.
 */
@TestConfiguration
public class KafkaTestSupportConfig {

    /**
     * KafkaProducerStub을 KafkaTemplate 자리에 등록.
     * Spring Boot의 KafkaAutoConfiguration은 @ConditionalOnMissingBean(KafkaTemplate.class)라
     * 우리가 직접 KafkaTemplate(의 하위 타입)을 등록하면 자동 설정이 비활성화된다.
     * @Primary로 다른 곳에 KafkaTemplate이 정의돼 있어도 우리 것이 우선되도록 보강.
     */
    @Bean
    @Primary
    public KafkaProducerStub kafkaProducerStub(ProducerFactory<String, String> producerFactory) {
        return new KafkaProducerStub(producerFactory);
    }

    @Bean
    public KafkaTestConsumer kafkaTestConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaTestConsumer(bootstrapServers);
    }
}
