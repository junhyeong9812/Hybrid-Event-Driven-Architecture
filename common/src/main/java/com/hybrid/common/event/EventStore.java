package com.hybrid.common.event;

import java.util.List;

/**
 * 이벤트의 영속·읽기 추상 — append-only 커밋 로그의 인터페이스.
 *
 * 사상: "EventBus가 메시지 라우터라면, EventStore는 사건의 역사."
 *   - WAL(Write-Ahead Log)에서 영감.
 *   - Kafka 토픽의 인메모리 축소판.
 *   - 구현체는 인메모리 / DB / Kafka 토픽 등으로 교체 가능.
 *
 * 이 추상 덕분에 Phase 1의 InMemoryEventStore가 Phase 2의 outbox 테이블로 사상적으로 진화한다.
 */
public interface EventStore {
    // 이벤트를 로그 끝에 추가하고, 부여된 offset을 반환한다.
    // offset은 단조 증가 — 전역 발생 순서의 정의.
    long append(DomainEvent event);

    // 지정한 offset부터 count개의 이벤트를 읽어온다.
    // 끝을 넘으면 짧은 리스트 / 빈 리스트를 돌려줘도 된다.
    List<DomainEvent> read(long from, int count);

    // 다음 append 시 부여될 offset (= 현재까지 저장된 이벤트 수).
    long latestOffset();
}
