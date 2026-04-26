package com.hybrid.notification.service;

import java.time.Instant;

/**
 * Circuit OPEN 동안 보류된 메일의 불변 표현.
 *
 * record:
 *   - 단순 데이터 캐리어 (도메인 의미 없음, 큐에 잠깐 머무는 그릇).
 *   - 불변성으로 큐 안에서 안전.
 *
 * queuedAt:
 *   언제 보류됐는지 — 너무 오래된 메일은 운영 정책상 폐기 등을 위한 정보.
 */
public record DeferredEmail(String to, String content, Instant queuedAt) {
    /** 편의 생성자 — 호출 시점이 자동으로 queuedAt이 됨. */
    public DeferredEmail(String to, String content) { this(to, content, Instant.now()); }
}
