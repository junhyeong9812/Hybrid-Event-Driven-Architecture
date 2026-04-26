package com.hybrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 모놀리스 진입점.
 *
 * com.hybrid 패키지 아래의 모든 컴포넌트를 자동 스캔한다:
 *   - com.hybrid.common.*    : EventBus, Outbox, Sweeper, Cleaner, Metrics, ...
 *   - com.hybrid.order.*     : Order 도메인
 *   - com.hybrid.payment.*   : Payment 도메인
 *   - com.hybrid.notification.* : Notification 도메인 + Inbox + Kafka 컨슈머
 *   - com.hybrid.admin.*     : 운영 admin 엔드포인트
 *   - com.hybrid.recovery.*  : OrderRecoveryJob 등 cross-domain orchestration
 *
 * @EnableScheduling은 OutboxRelay, OutboxCleaner, DeadLetterSweeper, OrderRecoveryJob,
 * DeferredEmailQueue 등 @Scheduled 빈을 활성화한다.
 *
 * Notification 모듈이 가진 NotificationApplication은 Phase 3의 단독 실행용 별도 진입점이다.
 */
@SpringBootApplication
@EnableScheduling
public class HybridEventDrivenApplication {
    public static void main(String[] args) {
        SpringApplication.run(HybridEventDrivenApplication.class, args);
    }
}
