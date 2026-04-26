package com.hybrid.notification.service;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeferredEmailQueue {

    private static final Logger log = LoggerFactory.getLogger(DeferredEmailQueue.class);

    private final Queue<DeferredEmail> queue = new ConcurrentLinkedQueue<>();
    private final EmailSender sender;

    public DeferredEmailQueue(EmailSender sender) { this.sender = sender; }

    public void enqueue(DeferredEmail email) { queue.add(email); }

    public int size() { return queue.size(); }

    @Scheduled(fixedDelay = 30_000)
    public void drain() {
        DeferredEmail e;
        while ((e = queue.poll()) != null) {
            try {
                sender.send(e.to(), e.content());        // Circuit이 닫혔으면 정상 발송
            } catch (Exception ex) {
                log.warn("re-deferring email to {}", e.to(), ex);
                queue.add(e);                            // 여전히 OPEN이면 다시 보관
                return;                                  // 한 건 실패하면 다음 사이클로
            }
        }
    }

    public Optional<DeferredEmail> peek() { return Optional.ofNullable(queue.peek()); }
}