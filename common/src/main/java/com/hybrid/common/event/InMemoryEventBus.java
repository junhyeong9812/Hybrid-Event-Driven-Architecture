package com.hybrid.common.event;

// 자료구조 / 동시성 / 함수형 인터페이스.
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// 핸들러 예외를 삼키되 로그로 남기기 위한 SLF4J.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 인메모리 EventBus 구현 — 동기 라우터.
 *
 * 동작:
 *   1. publish 시 EventStore에 append (사건의 기록을 먼저 남김)
 *   2. 등록된 구독자들에게 순차 호출
 *   3. 한 핸들러의 예외가 다른 핸들러를 막지 않도록 격리
 *
 * 동시성 자료구조 선택 근거:
 *   - handlers (Map): ConcurrentHashMap — 다른 이벤트 타입 등록은 동시에 가능 (버킷 락).
 *   - handlers의 각 List: CopyOnWriteArrayList — subscribe는 가뭄, publish 시 순회 폭우.
 *     CoW가 정확히 이 워크로드에 맞음. 순회 중 add 들어와도 ConcurrentModificationException 없음.
 */
public class InMemoryEventBus implements EventBus {

    // 영속·역사 책임을 위임받은 Store.
    private final EventStore store;

    // 이벤트 타입별 핸들러 목록.
    // 키: 이벤트 클래스, 값: 그 타입의 모든 구독자.
    private final Map<Class<? extends DomainEvent>, List<Consumer<DomainEvent>>> handlers
            = new ConcurrentHashMap<>();

    // 핸들러 예외 발생 시 로그.
    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    // 생성자 주입 — Store는 Bus 없이 만들 수 있지만 Bus는 Store가 필수.
    public InMemoryEventBus(EventStore store) { this.store = store; }

    @Override
    @SuppressWarnings("unchecked")  // 제네릭 타입 소거로 인한 unchecked 캐스트는 publish 시 키 매칭으로 안전.
    public <T extends DomainEvent> void subscribe(Class<T> type, Consumer<T> handler) {
        // computeIfAbsent: 타입 키가 없으면 새 CoW 리스트 만들고, 있으면 기존 리스트 반환.
        // ConcurrentHashMap에서 원자적 — 같은 타입에 동시 subscribe해도 List가 두 번 만들어지지 않음.
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                // Consumer<T>를 Consumer<DomainEvent>로 unchecked 캐스트.
                // publish가 event.getClass() 키로 정확한 타입의 핸들러만 꺼내므로 런타임 안전.
                .add((Consumer<DomainEvent>) handler);
    }

    @Override
    public void publish(DomainEvent event) {
        // 1) 사건의 기록 — Bus가 라우터지만 영속은 Store가 책임.
        //    publish가 Store에 append하는 순간 offset이 부여됨.
        store.append(event);

        // 2) 핸들러 디스패치.
        //    getOrDefault: 등록된 핸들러가 없으면 빈 리스트 — null 체크 없이 빈 순회.
        //    CoW 리스트의 iterator는 스냅샷 기반 — 순회 중 다른 스레드의 add는 무시됨.
        for (Consumer<DomainEvent> h : handlers.getOrDefault(event.getClass(), List.of())) {
            try {
                // 핸들러 호출 — 도메인 코드(예: PaymentEventHandler.onOrderCreated)로 진입.
                h.accept(event);
            } catch (Exception e) {
                // 한 핸들러의 예외가 다른 핸들러를 중단시키지 않도록 catch.
                // 예외 자체는 사라지지 않고 로그로 남음 → 운영 시 ELK/Loki 등에서 추적 가능.
                log.error("handler failed for {}", event, e);
            }
        }
    }
}
