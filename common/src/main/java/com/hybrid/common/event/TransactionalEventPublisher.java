package com.hybrid.common.event;

// Spring 빈으로 등록.
import org.springframework.stereotype.Component;
// TransactionSynchronization: 트랜잭션 라이프사이클 콜백 인터페이스.
import org.springframework.transaction.support.TransactionSynchronization;
// 현재 스레드의 트랜잭션 컨텍스트 (ThreadLocal)에 접근하는 정적 게이트웨이.
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "이벤트는 트랜잭션 커밋 후에만 발행, 롤백 시 폐기"를 보장하는 어댑터.
 *
 * 풀어주는 함정 — 이중 쓰기:
 *   - 도메인 저장 + 인메모리 디스패치를 그냥 순서대로 하면:
 *     "DB에 저장됐는데 핸들러 호출 후 트랜잭션 롤백" 또는
 *     "저장 안 됐는데 핸들러는 이미 동작" 같은 상황이 발생.
 *   - afterCommit 훅에 디스패치를 예약하면:
 *     커밋이 확정된 뒤에만 다른 도메인이 그 사실을 알게 됨 → 강한 일관성.
 *
 * Phase 2의 outbox INSERT가 같은 트랜잭션에 들어가면서 이 보장이 더 견고해진다.
 */
@Component
public class TransactionalEventPublisher {

    // 실제 디스패치는 InMemoryEventBus가 한다 — 이 클래스는 "언제" 디스패치할지만 결정.
    private final InMemoryEventBus bus;

    // 생성자 주입 — Spring이 InMemoryEventBus 빈을 자동 주입.
    public TransactionalEventPublisher(InMemoryEventBus bus) { this.bus = bus; }

    public void publish(DomainEvent event) {
        // 트랜잭션이 활성화 안 된 호출(예: 부팅 초기화 / 스케줄러 진입점)은
        // 즉시 발행 — fail-safe 동작. 호출자가 트랜잭션 안에 있길 강제하면 깨지기 쉬움.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bus.publish(event);
            return;
        }

        // 트랜잭션 활성 상태라면 — 익명 TransactionSynchronization을 등록.
        // (TransactionSynchronization은 함수형 인터페이스가 아니라서 람다 대신 익명 클래스 사용)
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 트랜잭션이 성공적으로 커밋된 직후에만 호출됨.
                        // 롤백되면 이 메서드는 호출 안 됨 → 이벤트 자동 폐기.
                        // 람다 캡처 — event 매개변수가 effectively final이라 가능.
                        bus.publish(event);
                    }
                }
        );
    }
}
