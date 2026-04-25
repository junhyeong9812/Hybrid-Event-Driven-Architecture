package com.hybrid.payment.service;

import java.math.BigDecimal;

import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;
import com.hybrid.common.event.contract.PaymentCompleted;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository repo;
    private final TransactionalEventPublisher publisher;

    public PaymentService(PaymentRepository repo, TransactionalEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Transactional
    public void process(Long orderId, BigDecimal amount) {
        Payment p = Payment.request(orderId, amount);
        repo.save(p);
        p.complete();           // Mock 결제: 즉시 성공
        publisher.publish(new PaymentCompleted(orderId));
    }
}