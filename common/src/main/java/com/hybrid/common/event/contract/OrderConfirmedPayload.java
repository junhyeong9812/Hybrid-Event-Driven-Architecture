package com.hybrid.common.event.contract;

import java.math.BigDecimal;

public record OrderConfirmedPayload(Long orderId, BigDecimal amount) {}
