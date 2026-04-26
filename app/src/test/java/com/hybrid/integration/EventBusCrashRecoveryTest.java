package com.hybrid.integration;

import java.math.BigDecimal;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;
import com.hybrid.recovery.OrderRecoveryJob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * JVM 크래시 시나리오에서 OrderRecoveryJob이 stuck 주문을 복구하는지 검증.
 *
 * app/integration 위치 — Order(도메인) + Payment(도메인) + OrderRecoveryJob(orchestration)
 * 모두 다루는 E2E 검증이라 조립체 모듈에 둔다.
 *
 * 가짜 헬퍼(awaitPaymentCompleted, forceStatus) 대신:
 *   - 실제 Phase 1 체인이 Payment를 COMPLETED로 만들 때까지 await
 *   - JdbcTemplate으로 order 상태와 created_at을 직접 강제 (recovery 조건 만족시키기 위해)
 */
class EventBusCrashRecoveryTest extends KafkaIntegrationTestBase {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired OrderRecoveryJob recoveryJob;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 결제_완료_후_JVM_크래시_시나리오에서_재시작하면_주문이_CONFIRMED로_복구된다() {
        // [Arrange] Phase 1 체인이 자동 실행 — 주문 생성 → 결제 처리
        Long orderId = orderService.create(new CreateOrderCommand(1L, BigDecimal.TEN));

        // Payment가 COMPLETED가 될 때까지 대기 (PaymentEventHandler가 비동기로 처리)
        await().atMost(3, SECONDS).untilAsserted(() ->
            assertThat(paymentRepo.findByOrderIdAndStatus(orderId, PaymentStatus.COMPLETED))
                .isPresent());

        // [Act 1] 크래시 시뮬레이션:
        //  - Order 상태를 PAYMENT_PENDING으로 강제 (Phase 1 체인이 정상 종료되지 못한 상태)
        //  - created_at을 5분 전으로 당김 (recoveryJob의 60초 임계 통과시키기 위해)
        jdbc.update(
            "UPDATE orders SET status = ?, created_at = NOW() - INTERVAL '5 minutes' WHERE id = ?",
            OrderStatus.PAYMENT_PENDING.name(), orderId);

        // [Act 2] 복구 잡 직접 실행
        recoveryJob.run();

        // [Assert] 주문이 CONFIRMED로 회복됨
        assertThat(orderRepo.findById(orderId).orElseThrow().status())
                .isEqualTo(OrderStatus.CONFIRMED);
    }
}
