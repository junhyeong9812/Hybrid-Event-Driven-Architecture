package com.hybrid.notification.service;

import java.time.Instant;

public record DeferredEmail(String to, String content, Instant queuedAt) {
    public DeferredEmail(String to, String content) { this(to, content, Instant.now()); }
}