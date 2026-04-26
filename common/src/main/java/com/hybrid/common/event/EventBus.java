package com.hybrid.common.event;

// Consumer<T>: 입력 T를 받아 결과 없이 처리하는 함수형 인터페이스.
// 핸들러 = "이벤트를 받아 무언가 한다, 반환값은 없다"라는 자연스러운 매핑.
import java.util.function.Consumer;

/**
 * 이벤트 발행 + 구독의 추상.
 *
 * 사상: pub/sub 라우터. 메시지를 들고 있지 않고, 누구에게 보낼지만 안다.
 * 영속·역사 책임은 EventStore가 분리해 가져감.
 *
 * 구현체는 인메모리(InMemoryEventBus) 또는 외부 브로커 어댑터로 교체 가능.
 */
public interface EventBus {
    // 특정 이벤트 타입에 대한 구독자 등록.
    // 제네릭 메서드 — Class<T>와 Consumer<T>가 같은 T를 강제해 타입 안전.
    <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler);

    // 이벤트 발행 — 등록된 구독자들에게 디스패치.
    // (구현체에 따라 동기 / 비동기 / 트랜잭션 동기 등 다를 수 있음)
    void publish(DomainEvent event);
}
