package com.hybrid.common.outbox;

// JSON 직렬화 — Jackson은 Spring Boot 자동 구성된 ObjectMapper를 주입받아 사용.
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Outbox 행 INSERT의 단일 진입점.
 *
 * 책임 분리:
 *   - 도메인 서비스(OrderService 등)는 "어떤 사실을 발행할지"만 안다.
 *   - JSON 직렬화 + 예외 처리는 이 헬퍼가 책임.
 *
 * 예외 정책:
 *   JsonProcessingException은 거의 항상 프로그래머 오류(payload 클래스 정의 잘못).
 *   사용자가 할 수 있는 게 없으니 RuntimeException으로 wrap → @Transactional 자동 롤백.
 *   호출자 시그니처가 JSON 라이브러리 종속에서 자유로워짐.
 */
@Component
public class OutboxWriter {

    // outbox 테이블 영속.
    private final OutboxRepository repo;
    // Spring Boot가 자동 구성한 단일 ObjectMapper — 시스템 전체에서 같은 직렬화 정책.
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * outbox 행을 만들어 저장.
     * 호출자는 반드시 @Transactional 컨텍스트에서 호출해야 함 — 같은 트랜잭션에서
     * 도메인 변경과 함께 커밋되어야 Outbox 패턴의 원자성 보장.
     */
    public void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        String json;
        try {
            // 도메인 객체 → JSON 문자열.
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // checked 예외를 unchecked로 wrap.
            // - 호출자 API가 JSON 라이브러리에 종속되지 않음.
            // - RuntimeException이라 Spring @Transactional 자동 롤백 → outbox 빈 행 안 남음.
            throw new IllegalStateException(
                    "outbox payload 직렬화 실패: type=" + eventType + ", id=" + aggregateId, e);
        }
        // INSERT — 호출자의 @Transactional 안에서 도메인 변경과 함께 커밋됨.
        repo.save(OutboxEvent.of(aggregateType, aggregateId, eventType, json));
    }
}
