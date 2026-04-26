package com.hybrid.common.outbox;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox 테이블에서 DEAD_LETTER 상태인 행을 outbox_dlq 테이블로 옮기는 백그라운드 잡.
 *
 * 분리 이유:
 *   outbox는 "정상 흐름의 데이터"여야 함. DEAD_LETTER가 거기 남아있으면
 *   - 폴링·메트릭·인덱스에 노이즈로 작용.
 *   - 운영자가 재처리 시점에 outbox를 직접 수정해야 해서 위험.
 *   별도 테이블로 옮기면 outbox는 깨끗하고, dlq는 운영자 도구의 명확한 대상이 됨.
 */
@Component
public class DeadLetterSweeper {

    private final OutboxRepository outboxRepo;
    private final DeadLetterRepository dlqRepo;
    // 이관 횟수 누적 메트릭 — 비정상 트래픽 감지에 사용.
    private final MeterRegistry meter;

    public DeadLetterSweeper(OutboxRepository outboxRepo,
                             DeadLetterRepository dlqRepo,
                             MeterRegistry meter) {
        this.outboxRepo = outboxRepo;
        this.dlqRepo = dlqRepo;
        this.meter = meter;
    }

    /**
     * 1분마다 DEAD_LETTER 행을 일괄 이관.
     * 한 트랜잭션 안에서 INSERT(dlq) + DELETE(outbox)를 묶어 원자적.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void run() {
        // DEAD_LETTER 모두 조회 — 보통 0건 또는 매우 적음.
        List<OutboxEvent> dead = outboxRepo.findByStatus(OutboxStatus.DEAD_LETTER);
        for (OutboxEvent e : dead) {
            // outbox 행 → dlq 행 변환 후 INSERT.
            dlqRepo.save(DeadLetterEvent.from(e));
            // 원본 outbox 행 DELETE — 정상 데이터 흐름에서 제거.
            outboxRepo.delete(e);
            // 운영 메트릭 — DLQ로 보낸 누적 횟수.
            meter.counter("outbox.deadletter.moved").increment();
        }
    }
}
