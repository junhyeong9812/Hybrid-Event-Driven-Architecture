package com.hybrid.integration;

import com.hybrid.common.support.KafkaIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointTest extends KafkaIntegrationTestBase {

    @Autowired WebTestClient webTestClient;

    @Test
    void actuator_prometheus_엔드포인트에서_메트릭이_노출된다() {
        String body = webTestClient.get().uri("/actuator/prometheus")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("outbox_pending_count");
        assertThat(body).contains("outbox_publish_success_total");
        assertThat(body).contains("inbox_duplicate_count_total");
    }
}