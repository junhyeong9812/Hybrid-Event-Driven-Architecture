package com.hybrid.order.event;

import java.math.BigDecimal;

public record OrderConfirmedPayload(Long orderId, BigDecimal amount) {}