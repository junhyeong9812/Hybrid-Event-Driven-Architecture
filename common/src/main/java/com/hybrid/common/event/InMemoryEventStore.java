package com.hybrid.common.event;

// CopyOnWriteArrayList: 읽기 락 없이 동시 순회 안전, 쓰기는 락으로 직렬화 + 전체 복사.
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventStore의 인메모리 구현 — append-only 자료구조 + 단조 증가 offset.
 *
 * 동시성 전략:
 *   - log: CopyOnWriteArrayList — 순회 중 add가 들어와도 안전.
 *   - offset: AtomicLong — synchronized append 안에서 getAndIncrement.
 *   - append 자체는 synchronized — offset 부여와 add를 원자적으로 묶음.
 */
public class InMemoryEventStore implements EventStore {

    // 모든 이벤트를 시간 순으로 보관하는 단일 로그.
    // CoW: subscribe 측에서 매 read마다 락 없이 안전하게 순회 가능.
    private final List<DomainEvent> log = new CopyOnWriteArrayList<>();

    // 다음에 부여될 offset. 초기값 0 — 첫 append는 0을 받는다.
    private final AtomicLong offset = new AtomicLong(0);

    @Override
    public synchronized long append(DomainEvent event) {
        // 1) offset을 먼저 채간다 (동시 append끼리 충돌 안 나도록 atomic).
        long current = offset.getAndIncrement();
        // 2) 로그에 추가.
        log.add(event);
        // 3) 부여된 offset을 반환 — 호출자가 메시지 위치를 추적할 수 있게.
        return current;
    }

    @Override
    public List<DomainEvent> read(long from, int count) {
        // 끝 위치 계산 — 요청한 count보다 로그가 짧으면 잘라서 반환.
        int end = (int) Math.min(from + count, log.size());
        // from이 이미 끝을 넘었으면 빈 리스트.
        if (from >= log.size()) return List.of();
        // subList는 view라 안전을 위해 List.copyOf로 방어 복사.
        return List.copyOf(log.subList((int) from, end));
    }

    @Override
    public long latestOffset() {
        // 다음 append가 부여받을 offset — 즉 현재까지 저장된 개수.
        return offset.get();
    }
}
