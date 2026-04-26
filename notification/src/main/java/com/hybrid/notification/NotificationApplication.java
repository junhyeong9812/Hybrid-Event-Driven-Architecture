package com.hybrid.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification 모듈의 단독 실행 진입점 — Phase 3의 마이크로서비스 분리 가능성 증명.
 *
 * 동작:
 *   ./gradlew :notification:bootRun
 *   → notification 모듈만 부팅, Order/Payment 클래스는 클래스패스에 없음.
 *   → Kafka에서 order-events를 구독해 InboxConsumer 흐름만 동작.
 *
 * 모놀리스 진입점(HybridEventDrivenApplication)과 별개의 main —
 * 같은 코드를 두 가지 부팅 경로로 사용 가능.
 *
 * @EnableScheduling — DeferredEmailQueue.drain 같은 스케줄 잡 활성화.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
