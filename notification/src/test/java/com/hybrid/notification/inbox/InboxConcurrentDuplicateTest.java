package com.hybrid.notification.inbox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InboxConcurrentDuplicateTest extends KafkaIntegrationTestBase {

    @Autowired InboxConsumer inboxConsumer;
    @Autowired InboxRepository inboxRepository;

    @Test
    void 동시_중복_수신_시_UNIQUE_위반을_중복_스킵으로_처리한다() throws Exception {
        Runnable task = () -> inboxConsumer.consume("msg-1", "t", "{}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        f1.get(); f2.get();

        assertThat(inboxRepository.count()).isEqualTo(1);
    }
}