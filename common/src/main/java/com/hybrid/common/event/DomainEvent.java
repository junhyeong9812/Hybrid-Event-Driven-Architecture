package com.hybrid.common.event;

// 모든 도메인 이벤트가 공통으로 노출해야 할 메타데이터를 위한 import.
import java.time.Instant;
import java.util.UUID;

/**
 * 모든 도메인 이벤트의 최상위 인터페이스 — 이 시스템에서 흐르는 "사건"의 공통 계약.
 *
 * 사상: "이벤트는 단순한 메시지가 아니라 사건의 기록"이다.
 *   - eventId: 식별자 (중복 판별·상관관계 추적용)
 *   - occurredAt: 발생 시각 (감사 / 순서 분석용)
 *   - eventType: 외부에 노출되는 이름 (Kafka 헤더 등에 사용)
 *   - aggregateId: 같은 도메인 인스턴스에서 발생한 이벤트를 묶는 키 (Kafka 파티션 키 등)
 *
 * default 메서드로 임시 값을 제공하지만, 호출 때마다 다른 값을 돌려주면 테스트가 불안정해지므로
 * 실제 구현은 AbstractDomainEvent를 상속해 인스턴스 필드로 고정값을 보관한다.
 */
public interface DomainEvent {
    // 매번 새로 생성되는 UUID — AbstractDomainEvent에서 인스턴스 필드로 캐싱됨.
    default UUID eventId() { return UUID.randomUUID(); }
    // 호출 시점의 현재 시각 — 마찬가지로 AbstractDomainEvent에서 한 번만 결정됨.
    default Instant occurredAt() { return Instant.now(); }
    // 구현체별 이벤트 이름. Kafka 헤더의 "eventType"으로도 직렬화된다.
    String eventType();
    // 같은 애그리거트(예: Order id=42)에서 나온 이벤트들을 묶는 식별자.
    // Kafka에서 같은 키는 같은 파티션으로 → 순서 보장 단위.
    String aggregateId();
}
