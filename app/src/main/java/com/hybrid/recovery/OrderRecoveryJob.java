package com.hybrid.recovery;

import java.time.Instant;
import java.util.List;

import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.domain.OrderStatus;
import com.hybrid.order.service.OrderService;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.payment.domain.PaymentStatus;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여러 도메인을 가로지르는 운영(orchestration) 작업.
 *
 * order 모듈이 payment를 의존하면 모듈 경계 위반이라 app 모듈의 recovery 패키지에 둔다.
 * "운영 잡은 조립체(app)의 책임" — DeadLetterAdminController와 같은 사상.
 *
 * 도메인 객체의 confirm()을 직접 부르지 않고 OrderService.confirm()을 호출한다 —
 * Phase 2의 outbox INSERT 흐름을 우회하지 않기 위함.
 */
@Component
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    private final OrderRepository orderRepo;
    private final OrderService orderService;
    private final PaymentRepository paymentRepo;
    private final MeterRegistry meter;

    public OrderRecoveryJob(OrderRepository orderRepo,
                            OrderService orderService,
                            PaymentRepository paymentRepo,
                            MeterRegistry meter) {
        this.orderRepo = orderRepo;
        this.orderService = orderService;
        this.paymentRepo = paymentRepo;
        this.meter = meter;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void run() {
        List<Order> stuck = orderRepo.findByStatusAndCreatedAtBefore(
                OrderStatus.PAYMENT_PENDING, Instant.now().minusSeconds(60));

        for (Order o : stuck) {
            paymentRepo.findByOrderIdAndStatus(o.getId(), PaymentStatus.COMPLETED)
                    .ifPresent(p -> {
                        log.warn("recovering stuck order {}", o.getId());
                        orderService.confirm(o.getId());   // outbox INSERT 포함된 정식 경로
                        meter.counter("order.recovered").increment();
                    });
        }
    }
}
