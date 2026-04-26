package com.hybrid.admin;

import com.hybrid.common.outbox.DeadLetterRepository;
import com.hybrid.common.outbox.OutboxRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 DLQ 수동 재시도 엔드포인트.
 *
 * common 모듈이 아니라 app 모듈에 있는 이유:
 *  - common은 web 의존성을 의도적으로 안 가짐 (인프라 모듈, 비-HTTP).
 *  - 운영 관리(admin) 기능은 조립체(app)의 책임.
 *  - 미래에 별도 admin-service로 분리하기 쉬움 — 도메인 모듈 영향 없이.
 */
@RestController
@RequestMapping("/admin/dlq")
public class DeadLetterAdminController {

    private final DeadLetterRepository dlqRepo;
    private final OutboxRepository outboxRepo;

    public DeadLetterAdminController(DeadLetterRepository dlqRepo, OutboxRepository outboxRepo) {
        this.dlqRepo = dlqRepo;
        this.outboxRepo = outboxRepo;
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        return dlqRepo.findById(id)
                .map(dlq -> {
                    outboxRepo.save(dlq.toRetryable());
                    dlqRepo.delete(dlq);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
