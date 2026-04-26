package com.hybrid.payment.service;

import java.math.BigDecimal;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.PaymentCompleted;
import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 처리 유스케이스.
 *
 * Phase 1 구현은 Mock — 실제 외부 결제사 호출 없이 즉시 complete().
 * 운영에선 Phase 3에서 도입한 EmailGateway 패턴처럼 PaymentGateway 인터페이스로
 * 외부 결제사 어댑터를 분리할 자리.
 */
@Service
public class PaymentService {

    private final PaymentRepository repo;
    // 트랜잭션 동기화로 안전한 PaymentCompleted 발행.
    private final TransactionalEventPublisher publisher;

    public PaymentService(PaymentRepository repo, TransactionalEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    /**
     * 결제 처리 — OrderCreated를 받은 PaymentEventHandler가 호출.
     *
     * 트랜잭션 안에서:
     *   1) Payment 객체 생성 + 영속화
     *   2) 즉시 complete() (Mock)
     *   3) PaymentCompleted 인메모리 발행 (afterCommit 예약)
     */
    @Transactional
    public void process(Long orderId, BigDecimal amount) {
        // 도메인 팩토리 — 양수 검증 포함.
        Payment p = Payment.request(orderId, amount);
        // INSERT — JPA가 ID 부여.
        repo.save(p);
        // Mock 결제 — 실제론 외부 API 호출이 들어갈 자리. 여기선 즉시 성공 처리.
        p.complete();
        // 도메인 → 다른 도메인 통신 — Order가 이걸 받아 confirm 호출.
        publisher.publish(new PaymentCompleted(orderId));
    }
}
