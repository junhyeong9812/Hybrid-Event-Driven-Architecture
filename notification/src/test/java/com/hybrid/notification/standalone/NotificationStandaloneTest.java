package com.hybrid.notification.standalone;

import java.util.Map;

import com.hybrid.common.support.KafkaTestProducer;
import com.hybrid.notification.NotificationApplication;
import com.hybrid.notification.inbox.InboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = NotificationApplication.class,
        properties = { "spring.main.web-application-type=none" })
@Testcontainers
class NotificationStandaloneTest {

    @Container static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired KafkaTestProducer producer;
    @Autowired InboxRepository inboxRepo;

    @Test
    void Notification은_Order_Payment없이_Kafka_메시지만으로_동작한다() {
        producer.send("order-events", "42",
                "{\"orderId\":42}", Map.of("messageId","m1","eventType","OrderConfirmed"));

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(inboxRepo.existsByMessageId("m1")).isTrue());
    }
}