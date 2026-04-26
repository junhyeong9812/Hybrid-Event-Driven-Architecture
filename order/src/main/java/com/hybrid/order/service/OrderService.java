package com.hybrid.order.service;

// 트랜잭션 동기화로 안전한 이벤트 발행.
import com.hybrid.common.event.TransactionalEventPublisher;
// 도메인 간 통신 컨트랙트 (common.event.contract).
import com.hybrid.common.event.contract.OrderConfirmed;
import com.hybrid.common.event.contract.OrderConfirmedPayload;
import com.hybrid.common.event.contract.OrderCreated;
// Outbox 패턴 — Phase 2에서 도입된 외부 통신 종단.
import com.hybrid.common.outbox.OutboxRepository;
import com.hybrid.common.outbox.OutboxWriter;
// 자기 도메인 — 순환 의존 없음.
import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인의 유스케이스 진입점.
 *
 * 사상:
 *   - 모든 메서드가 @Transactional — 도메인 변경 + 이벤트 발행 + outbox INSERT가
 *     같은 단위로 commit / rollback.
 *   - 인메모리 EventBus(내부 통신)와 Outbox(외부 통신)를 둘 다 발행 — Phase 1+2의 누적.
 */
@Service
public class OrderService {

    private final OrderRepository repo;
    // outbox 직접 사용은 거의 없음 — OutboxWriter를 통해 추상.
    private final OutboxRepository outboxRepository;
    // 인메모리 이벤트 발행 — afterCommit에 디스패치 예약.
    private final TransactionalEventPublisher publisher;
    // outbox 행 INSERT 헬퍼 — JSON 직렬화 + 예외 wrap 책임.
    private final OutboxWriter outboxWriter;

    public OrderService(OrderRepository repo,
                        OutboxRepository outboxRepository,
                        TransactionalEventPublisher publisher,
                        OutboxWriter outboxWriter) {
        this.repo = repo;
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.outboxWriter = outboxWriter;
    }

    /**
     * 주문 생성 유스케이스.
     * 도메인 객체 생성 + 영속화 + OrderCreated 이벤트 발행 (인메모리만).
     * Phase 2의 outbox INSERT는 confirm()에 — create는 내부 흐름 트리거이지 외부 노출 사실 아님.
     */
    @Transactional
    public Long create(CreateOrderCommand cmd) {
        // 도메인 팩토리 — 불변식 검증 후 객체 생성.
        Order order = Order.create(cmd.customerId(), cmd.amount());
        // INSERT — JPA가 ID 자동 부여.
        repo.save(order);
        // 인메모리 이벤트 발행 — afterCommit 예약 → Payment 도메인이 이걸 받아 결제 시작.
        publisher.publish(new OrderCreated(order.getId(), order.getAmount()));
        // 컨트롤러가 응답 Location 헤더에 사용하기 위해 ID 반환.
        return order.getId();
    }

    /**
     * 주문 확정 유스케이스 — 결제 완료 후 호출됨.
     *
     * 트랜잭션 안에서 세 가지가 함께 일어남:
     *   1) 도메인 상태 전이 (CREATED → CONFIRMED)
     *   2) outbox 행 INSERT (외부로 나갈 OrderConfirmed 이벤트)
     *   3) 인메모리 OrderConfirmed 발행 (내부 도메인 통신, 현재는 구독자 없음)
     *
     * 셋 다 같은 트랜잭션 — 부분 성공 없음.
     */
    @Transactional
    public void confirm(Long orderId) {
        // 도메인 객체 로드 — 없으면 NoSuchElementException.
        Order o = repo.findById(orderId).orElseThrow();
        // 도메인 메서드 호출 — 상태 전이 규칙 검증 포함.
        o.confirm();

        // outbox INSERT — Phase 2의 핵심.
        // OutboxWriter가 JSON 직렬화 + 예외 wrap 처리.
        outboxWriter.write("Order", orderId.toString(), "OrderConfirmed",
                new OrderConfirmedPayload(orderId, o.getAmount()));

        // 인메모리 발행 — 현재 구독자는 없지만 미래 확장 / 디버깅용.
        publisher.publish(new OrderConfirmed(orderId));
    }

    /**
     * 테스트 전용 — "트랜잭션 롤백 시 outbox에도 안 남는다"를 검증할 때 사용.
     * 정상 create 후 강제 RuntimeException → 트랜잭션 롤백.
     * 운영 코드에서 호출되지 않는 진단용 메서드.
     */
    @Transactional
    public Long createWithForcedRollback(CreateOrderCommand cmd) {
        Long id = create(cmd);
        throw new RuntimeException("forced rollback for test");
    }

    /**
     * 테스트 전용 — confirm + 롤백 조합. outbox INSERT가 함께 폐기되는지 검증.
     */
    @Transactional
    public void confirmWithForcedRollback(Long orderId) {
        confirm(orderId);
        throw new RuntimeException("forced rollback for test");
    }
}
