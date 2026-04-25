package com.hybrid.common.event;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import com.hybrid.common.event.EventStore;
import com.hybrid.common.event.TransactionalEventPublisher;
import com.hybrid.common.event.contract.OrderCreated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class TransactionalEventPublishingTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired TransactionalEventPublisher publisher;
    @Autowired PlatformTransactionManager txm;
    @Autowired EventStore store;

    @Test
    void 트랜잭션_커밋_후에_이벤트가_디스패치된다() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        AtomicLong offsetDuringTx = new AtomicLong(-1);

        tx.executeWithoutResult(status -> {
            publisher.publish(new OrderCreated(1L, BigDecimal.TEN));
            offsetDuringTx.set(store.latestOffset());        // 아직 append 전
        });

        assertThat(offsetDuringTx.get()).isZero();
        assertThat(store.latestOffset()).isEqualTo(1L);       // 커밋 후
    }

    @Test
    void 트랜잭션_롤백_시_이벤트는_폐기된다() {
        TransactionTemplate tx = new TransactionTemplate(txm);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publish(new OrderCreated(1L, BigDecimal.TEN));
            throw new RuntimeException("rollback");
        })).isInstanceOf(RuntimeException.class);

        assertThat(store.latestOffset()).isZero();
    }
}