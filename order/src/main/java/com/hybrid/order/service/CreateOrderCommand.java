package com.hybrid.order.service;

import java.math.BigDecimal;

/**
 * 주문 생성 입력 — 불변 커맨드 객체.
 * record 사용: 도메인이 아닌 단순 데이터 캐리어 + 외부에서 조작 불가.
 *
 * 컨트롤러가 HTTP 요청 본문을 이 record로 바인딩 → Service에 전달.
 * 테스트 코드도 같은 record로 시그니처 일치.
 */
public record CreateOrderCommand(Long customerId, BigDecimal amount) {}
