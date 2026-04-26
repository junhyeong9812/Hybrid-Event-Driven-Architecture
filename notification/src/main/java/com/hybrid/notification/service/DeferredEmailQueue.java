package com.hybrid.notification.service;

import java.util.Optional;
import java.util.Queue;
// 락 없는 동시 큐 — 단일 producer + 단일 consumer 시나리오에 적합.
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Circuit OPEN 동안 보류된 메일을 회복 후 재발송하는 큐.
 *
 * Bulkhead 패턴의 일부:
 *   - Circuit Breaker가 빠른 실패로 시스템 전체 마비를 막음.
 *   - 하지만 메일 자체는 잃으면 안 됨 → 큐에 잠깐 보관.
 *   - 회복(Circuit CLOSED) 후 자동 drain.
 *
 * 한계 — 인메모리 큐:
 *   JVM 크래시 시 큐 휘발 가능. 운영 시스템에선 DB나 Redis로 보내는 게 정답.
 *   학습 단계에선 인메모리 큐로 패턴 사상만 보여줌.
 */
@Component
public class DeferredEmailQueue {

    private static final Logger log = LoggerFactory.getLogger(DeferredEmailQueue.class);

    // ConcurrentLinkedQueue: lock-free, 동시 enqueue/poll 안전.
    private final Queue<DeferredEmail> queue = new ConcurrentLinkedQueue<>();
    // 다시 발송 시도할 sender — 같은 빈, Circuit Breaker가 감싼 것.
    private final EmailSender sender;

    public DeferredEmailQueue(EmailSender sender) { this.sender = sender; }

    /** EmailSender.fallback이 호출 — Circuit OPEN 동안 들어오는 메일 보관. */
    public void enqueue(DeferredEmail email) { queue.add(email); }

    /** 운영 가시화 — 큐 길이 모니터링용 (Gauge로 노출 가능). */
    public int size() { return queue.size(); }

    /**
     * 30초마다 큐를 비워본다.
     * Circuit이 닫혔으면 정상 발송, 여전히 OPEN이면 fallback이 다시 enqueue → 영구 루프 방지를 위해 한 건 실패 시 sleep.
     */
    @Scheduled(fixedDelay = 30_000)
    public void drain() {
        DeferredEmail e;
        // poll: 큐에서 하나 꺼내 반환 (없으면 null).
        while ((e = queue.poll()) != null) {
            try {
                // Circuit이 닫혔으면 정상 발송 → markSent까지.
                sender.send(e.to(), e.content());
            } catch (Exception ex) {
                // 여전히 OPEN이거나 다른 실패 → 다시 큐에 넣고 다음 사이클로.
                log.warn("re-deferring email to {}", e.to(), ex);
                queue.add(e);
                // return으로 빠져나감 — 무한 루프 방지 + 시스템에 호흡 시간.
                return;
            }
        }
    }

    /** 운영 / 디버깅용 — 큐의 첫 메일 미리보기. */
    public Optional<DeferredEmail> peek() { return Optional.ofNullable(queue.peek()); }
}
