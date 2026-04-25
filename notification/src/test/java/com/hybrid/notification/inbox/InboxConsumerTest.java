package com.hybrid.notification.inbox;

import com.hybrid.common.support.KafkaIntegrationTestBase;
import com.hybrid.notification.service.NotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class InboxConsumerTest extends KafkaIntegrationTestBase {

    @Autowired InboxConsumer inboxConsumer;
    @Autowired InboxRepository inboxRepository;
    @MockitoBean NotificationService notificationService;

    @Test
    void 같은_messageId가_두_번_들어오면_두_번째는_스킵된다() {
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{\"orderId\":1}");
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{\"orderId\":1}");

        assertThat(inboxRepository.count()).isEqualTo(1);
        verify(notificationService, times(1)).process(any(), any());
    }

    @Test
    void 서로_다른_messageId는_각각_처리된다() {
        inboxConsumer.consume("msg-1", "OrderConfirmed", "{}");
        inboxConsumer.consume("msg-2", "OrderConfirmed", "{}");

        assertThat(inboxRepository.count()).isEqualTo(2);
        verify(notificationService, times(2)).process(any(), any());
    }

    @Test
    void 비즈니스_로직_예외_시_inbox에도_기록되지_않는다() {
        doThrow(new RuntimeException("boom")).when(notificationService).process(any(), any());

        assertThatThrownBy(() ->
                inboxConsumer.consume("msg-1", "OrderConfirmed", "{}"))
                .isInstanceOf(RuntimeException.class);

        assertThat(inboxRepository.existsByMessageId("msg-1")).isFalse();
    }
}