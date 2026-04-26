package com.hybrid.payment.web;

import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 도메인 HTTP 진입점 — 단일 조회 엔드포인트만.
 *
 * 결제 생성은 외부에서 직접 호출되지 않음 — OrderCreated 이벤트가 트리거.
 * 따라서 POST 엔드포인트 없음. 운영자가 결제 상태를 확인할 GET만 노출.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository repo;

    public PaymentController(PaymentRepository repo) { this.repo = repo; }

    /** 주문ID로 결제 조회 — 1:1 관계 가정. */
    @GetMapping("/{orderId}")
    public ResponseEntity<Payment> getByOrder(@PathVariable Long orderId) {
        return repo.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
