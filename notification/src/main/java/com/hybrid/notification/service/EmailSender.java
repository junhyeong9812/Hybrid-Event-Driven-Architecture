package com.hybrid.notification.service;

// Resilience4j Circuit Breaker — 외부 시스템 장애 격리.
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 이메일 발송 외부 호출의 격벽.
 *
 * 동작:
 *   - 정상: gateway.deliver(...) 호출.
 *   - 연속 실패 누적 → Circuit OPEN → 일정 시간 동안 호출 차단 (빠른 실패).
 *   - OPEN 동안 들어온 요청 → fallback() → DeferredEmailQueue에 보류.
 *   - 일정 시간 후 HALF_OPEN → 시험 호출 성공 시 CLOSED 복귀.
 *
 * 설정은 application.yml의 resilience4j.circuitbreaker.instances.email.* 에서.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    // 외부 메일 시스템 호출 추상 — production은 LoggingEmailGateway, 테스트는 EmailSenderStub.
    private final EmailGateway gateway;
    // OPEN 상태 동안 보류된 메일을 회복 후 재발송할 큐.
    private final DeferredEmailQueue deferred;

    public EmailSender(EmailGateway gateway, DeferredEmailQueue deferred) {
        this.gateway = gateway;
        this.deferred = deferred;
    }

    /**
     * 메일 발송. @CircuitBreaker가 AOP 프록시로 감싸 호출 횟수·실패율 추적.
     * fallbackMethod는 실패 시 호출될 메서드 이름 — 시그니처는 원래 메서드 + Throwable.
     */
    @CircuitBreaker(name = "email", fallbackMethod = "fallback")
    public void send(String to, String content) {
        gateway.deliver(to, content);   // 외부 SMTP/SaaS 호출 — 실패 가능성.
    }

    /**
     * Circuit OPEN 또는 호출 자체 실패 시 호출됨.
     * 메일을 잃지 않도록 큐에 보류 → DeferredEmailQueue.drain이 회복 후 재발송.
     */
    void fallback(String to, String content, Throwable t) {
        log.warn("email circuit open, message deferred: {}", to, t);
        deferred.enqueue(new DeferredEmail(to, content));
    }
}
