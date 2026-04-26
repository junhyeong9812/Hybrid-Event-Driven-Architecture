package com.hybrid.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Outbox 상태를 Prometheus 메트릭으로 노출하는 등록기.
 *
 * 게이지 두 개:
 *   - outbox.pending.count    : 발행 대기 중인 행 수 (0에 수렴해야 정상)
 *   - outbox.deadletter.count : 영구 실패한 행 수 (0이어야 정상, > 0이면 알람)
 *
 * 동작:
 *   생성자에서 람다(Supplier)로 등록 → Micrometer가 메트릭 노출 시점마다
 *   repo.countByStatus(...)를 호출. 부분 인덱스 덕분에 빠름.
 *
 * GC 주의:
 *   Gauge는 람다를 약한 참조로 들고 있어서 캡처된 객체가 GC되면 NaN 반환.
 *   이 클래스가 @Component로 빈 라이프사이클이라 repo도 함께 보존됨 → 안전.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repo) {
        // PENDING 행 게이지 — 폴링이 정체되면 이 값이 올라감.
        Gauge.builder("outbox.pending.count",
                        () -> repo.countByStatus(OutboxStatus.PENDING))
                .register(registry);
        // DEAD_LETTER 행 게이지 — 알람 트리거의 핵심.
        Gauge.builder("outbox.deadletter.count",
                        () -> repo.countByStatus(OutboxStatus.DEAD_LETTER))
                .register(registry);
    }
}
