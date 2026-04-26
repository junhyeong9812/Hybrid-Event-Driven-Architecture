package com.hybrid.integration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapDocumentTest {

    @Test
    void 분리_로드맵_문서가_존재하고_필수_섹션을_포함한다() throws Exception {
        Path doc = Path.of("docs/roadmap-order-payment-split.md");
        assertThat(Files.exists(doc)).isTrue();
        String content = Files.readString(doc);
        assertThat(content).contains("## 전환 전/후");
        assertThat(content).contains("## 단계별 시나리오");
        assertThat(content).contains("## 롤백 기준");
    }
}