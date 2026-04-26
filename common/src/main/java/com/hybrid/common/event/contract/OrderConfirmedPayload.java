package com.hybrid.common.event.contract;

import java.math.BigDecimal;

/**
 * Outbox JSON 직렬화 전용 페이로드 DTO.
 *
 * 왜 OrderConfirmed 이벤트를 그대로 직렬화하지 않는가:
 *   - OrderConfirmed는 도메인 이벤트 — eventId, occurredAt 같은 메타가 따라옴.
 *     → 외부 컨슈머에게 노출하지 않을 내부 메타.
 *   - 도메인 이벤트의 필드명은 내부 편의로 자주 변경됨.
 *     → 외부 계약(JSON 스키마)이 그것에 끌려가면 호환성 깨짐.
 *   - record는 AbstractDomainEvent를 상속 못 하니 도메인 이벤트로는 못 만듦.
 *     → 외부 노출 전용 불변 캐리어로 record가 정확히 맞음.
 *
 * 즉 "도메인의 의도(OrderConfirmed)"와 "외부 계약(JSON 스키마)"을 분리한다.
 */
public record OrderConfirmedPayload(Long orderId, BigDecimal amount) {}
