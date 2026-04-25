package com.hybrid.order.service;

import java.math.BigDecimal;

public record CreateOrderCommand(Long customerId, BigDecimal amount) {}