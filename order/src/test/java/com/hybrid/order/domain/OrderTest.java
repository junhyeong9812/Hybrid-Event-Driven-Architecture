package com.hybrid.order.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void 주문_생성_시_상태는_CREATED다() {
        Order o = Order.create(1L, BigDecimal.valueOf(1000));
        assertThat(o.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 금액은_양수여야_한다() {
        assertThatThrownBy(() -> Order.create(1L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void CREATED_상태에서만_confirm_가능하다() {
        Order o = Order.create(1L, BigDecimal.TEN);
        o.confirm();
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);

        assertThatThrownBy(o::confirm).isInstanceOf(IllegalStateException.class);
    }
}