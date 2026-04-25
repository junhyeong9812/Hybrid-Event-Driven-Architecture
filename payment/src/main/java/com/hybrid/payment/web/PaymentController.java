package com.hybrid.payment.web;

import com.hybrid.payment.domain.Payment;
import com.hybrid.payment.domain.PaymentRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository repo;

    public PaymentController(PaymentRepository repo) { this.repo = repo; }

    @GetMapping("/{orderId}")
    public ResponseEntity<Payment> getByOrder(@PathVariable Long orderId) {
        return repo.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}