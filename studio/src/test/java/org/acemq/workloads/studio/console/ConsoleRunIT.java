/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.workloads.studio.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Start this load, against a real broker: the designer's file runs, its readings reach the
 * standing-loads view while it goes, a second start is refused, and when it ends the library's
 * rules are kept as evidence with the numbers they used.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("a load started from the console")
class ConsoleRunIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("acemq-console-run-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("acemq.studio.database", () -> ROOT.resolve("studio.db").toString());
        registry.add("acemq.studio.workloads-dir", () -> ROOT.resolve("workloads").toString());
    }

    @Test
    @Timeout(240)
    @DisplayName("runs, shows its readings, refuses a second, and keeps its evidence")
    void runsAndKeepsEvidence() throws Exception {
        String yaml = WorkloadYaml.toYaml(new WorkloadYaml.Form("console-it",
                "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort(),
                null, null, "console.it", null, "classic", true,
                1, 200L, false, 256, false, true, null, null, 1, 50, null, null,
                "1s", "6s", null, null, "5s", null, true));

        ResponseEntity<String> started = http.postForEntity("/api/console/runs",
                Map.of("yaml", yaml), String.class);
        assertThat(started.getStatusCode()).as(started.getBody()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode c = json.readTree(http.getForObject("/api/console/loads", String.class))
                    .path("clients").get(0);
            assertThat(c.path("source").asText()).isEqualTo("studio");
            assertThat(c.path("history").size()).isPositive();
            assertThat(c.path("last").path("confirmed").asLong()).isPositive();
        });

        ResponseEntity<String> second = http.postForEntity("/api/console/runs",
                Map.of("yaml", yaml), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode run = await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
                .until(() -> json.readTree(http.getForObject("/api/console/evidence", String.class)),
                        list -> list.size() == 1).get(0);

        assertThat(run.path("name").asText()).isEqualTo("console-it");
        assertThat(run.path("published").asLong()).isPositive();
        assertThat(run.path("endToEnd").path("p99Ms").asDouble()).isPositive();
        assertThat(run.path("broker").asText()).doesNotContain("guest:guest");
        Map<String, JsonNode> rules = new java.util.HashMap<>();
        run.path("rules").forEach(r -> rules.put(r.path("id").asText(), r));
        assertThat(rules).containsKeys("generator-kept-up", "broker-not-blocked",
                "publishes-succeeded", "consumers-kept-up", "confirms-were-on",
                "tail-is-not-extreme", "run-was-long-enough", "p99<5000.0ms", "no-messages-lost");
        // Six seconds is short, and the library says so.
        assertThat(rules.get("run-was-long-enough").path("status").asText()).isEqualTo("warning");
        assertThat(rules.get("confirms-were-on").path("status").asText()).isEqualTo("passed");
        assertThat(rules.get("confirms-were-on").path("evidence").asText()).isEqualTo("confirms on");
        assertThat(rules.get("publishes-succeeded").path("evidence").asText()).startsWith("failed 0 of");
        assertThat(ROOT.resolve("workloads/runs").toFile().list()).hasSize(1);
    }
}
