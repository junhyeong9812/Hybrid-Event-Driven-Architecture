package com.hybrid.common.event;

// 인스턴스 생성 시점에 고정될 값을 위한 import.
import java.time.Instant;
import java.util.UUID;

/**
 * DomainEvent의 기본 추상 구현.
 *
 * 왜 필요한가:
 *   인터페이스의 default 메서드는 호출 때마다 다른 값을 돌려준다 (UUID.randomUUID(), Instant.now()).
 *   테스트가 같은 이벤트를 두 번 비교할 때 매번 새 값이 나오면 동등성 검사가 깨진다.
 *   인스턴스 필드로 한 번만 결정해 캐싱 → 같은 객체에 대해 같은 값을 보장.
 *
 * 구체 이벤트(OrderCreated 등)는 이 클래스를 extends해서 자기 비즈니스 필드만 추가하면 된다.
 */
public abstract class AbstractDomainEvent implements DomainEvent {
    // 객체가 만들어진 순간 한 번만 UUID 생성 → 같은 인스턴스의 eventId()는 항상 같음.
    private final UUID eventId = UUID.randomUUID();
    // 객체 생성 시점의 시각을 캐싱 → 호출 시점에 따라 값이 변하지 않음.
    private final Instant occurredAt = Instant.now();

    // 캐시된 UUID 반환 — 인터페이스의 default를 오버라이드.
    @Override public UUID eventId() { return eventId; }
    // 캐시된 발생 시각 반환.
    @Override public Instant occurredAt() { return occurredAt; }
}
