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
package org.acemq.workloads.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.scenario.ScenarioFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Pressing Run, against a real broker.
 *
 * <p>Everything else about the studio is a form over a file and is covered without a broker. This
 * is the part that is the product: start a run, watch readings arrive, get a report back, and find
 * it in the history afterwards. It was proved by hand until this existed, which meant a broken Run
 * button could be released and nothing would have noticed.
 *
 * <p>The rates are small deliberately. A container sharing a laptop with the generator is not a
 * capacity measurement, and what is asserted here is that the machinery works — not what the
 * broker can take.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("a run started from the studio")
class StudioRunIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static Path database;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @BeforeAll
    static void useATemporaryDatabase() throws Exception {
        database = Files.createTempFile("acemq-studio-run-it", ".db");
        Files.deleteIfExists(database);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("acemq.studio.database", () -> database.toString());
    }

    @AfterAll
    static void removeIt() throws Exception {
        Files.deleteIfExists(database);
    }

    private static String amqpUrl() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
    }

    @Test
    @Timeout(300)
    @DisplayName("runs, reports, and is there in the history afterwards")
    void runsAndReports() throws Exception {
        String id = start(aScenario(null));

        // While it runs there are readings. Without these the live view has nothing to draw, and
        // a studio whose charts stay empty is indistinguishable from one whose broker is dead.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(samples(id)).isNotEmpty());

        awaitFinished(id);

        JsonNode report = json.readTree(
                http.getForObject("/api/runs/" + id + "/report", String.class));

        assertThat(report.path("verdict").asText()).isEqualTo("passed");
        assertThat(report.path("valid").asBoolean()).isTrue();
        assertThat(report.path("totalPublished").asLong()).isPositive();
        assertThat(report.path("totalConsumed").asLong()).isPositive();
        assertThat(report.path("queues").get(0).path("consumed").asLong()).isPositive();

        // And the run is in the history, with the password gone from the broker URL it kept.
        JsonNode history = json.readTree(http.getForObject("/api/runs", String.class));
        JsonNode mine = find(history, id);
        assertThat(mine.path("status").asText()).isEqualTo("finished");
        assertThat(mine.path("verdict").asText()).isEqualTo("passed");
        assertThat(mine.path("broker").asText()).doesNotContain("guest:guest").contains("***");

        // The report can be taken away as a file. A run watched here and then described from
        // memory in a ticket is a run nobody else can check.
        ResponseEntity<String> page =
                http.getForEntity("/api/runs/" + id + "/report.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment").contains("acemq-report-it-studio");
        assertThat(page.getBody()).contains("<html").contains("it-studio.q");

        ResponseEntity<String> markdown =
                http.getForEntity("/api/runs/" + id + "/report.md", String.class);
        assertThat(markdown.getBody()).contains("# it-studio").contains("| it-studio.q |");

        // The password must not travel in a file somebody attaches to a ticket either.
        assertThat(page.getBody()).doesNotContain("guest:guest");
        assertThat(markdown.getBody()).doesNotContain("guest:guest");
    }

    // The whole point of the objectives: what the studio ran is what a pipeline would fail on.
    @Test
    @Timeout(300)
    @DisplayName("fails when an objective the scenario carries is missed")
    void failsAnObjectiveItCannotMeet() throws Exception {
        ScenarioFile impossible = aScenario(new ScenarioFile.ExpectJson(
                null, null, null, 900_000L, null, null, null, null));

        String id = start(impossible);
        awaitFinished(id);

        JsonNode report = json.readTree(
                http.getForObject("/api/runs/" + id + "/report", String.class));

        assertThat(report.path("verdict").asText()).isEqualTo("failed");
        assertThat(report.path("valid").asBoolean()).isTrue();
        assertThat(report.toString()).contains("expected-consume-rate:it-studio.q");
    }

    @Test
    @Timeout(300)
    @DisplayName("stops when asked, and reports on the window it measured")
    void stopsAndStillReports() throws Exception {
        String id = start(aScenario(null, "30s"));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(samples(id)).isNotEmpty());

        ResponseEntity<String> stopped =
                http.postForEntity("/api/runs/" + id + "/stop", null, String.class);
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);

        awaitFinished(id);

        JsonNode report = json.readTree(
                http.getForObject("/api/runs/" + id + "/report", String.class));

        // Stopped early is not thrown away: the run reports on what it did measure.
        assertThat(report.path("stoppedEarly").asBoolean()).isTrue();
        assertThat(report.path("totalPublished").asLong()).isPositive();
        assertThat(report.path("durationMs").asLong()).isLessThan(30_000);
    }

    /**
     * @param scenario what to run
     * @return the run's identifier
     */
    private String start(ScenarioFile scenario) {
        ResponseEntity<Map> response = http.postForEntity("/api/runs",
                Map.of("scenario", scenario, "broker", amqpUrl()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return String.valueOf(response.getBody().get("id"));
    }

    private void awaitFinished(String id) {
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(2))
                .until(() -> !http.getForObject("/api/runs/current", Map.class)
                        .get("running").equals(Boolean.TRUE));
    }

    private JsonNode samples(String id) throws Exception {
        return json.readTree(http.getForObject("/api/runs/" + id + "/samples", String.class));
    }

    private static JsonNode find(JsonNode runs, String id) {
        for (JsonNode run : runs) {
            if (id.equals(run.path("id").asText())) {
                return run;
            }
        }
        throw new AssertionError("run " + id + " is not in the history");
    }

    private static ScenarioFile aScenario(ScenarioFile.ExpectJson expect) {
        return aScenario(expect, "6s");
    }

    private static ScenarioFile aScenario(ScenarioFile.ExpectJson expect, String runFor) {
        return new ScenarioFile("it-studio", "a run started through the API", null, null,
                List.of(new ScenarioFile.ExchangeJson("it-studio.x", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("it-studio.q", "classic", null, null, null,
                        List.of(new ScenarioFile.BindingJson("it-studio.x", "#")),
                        new ScenarioFile.ConsumersJson(2, 100, null, null, null), null, expect)),
                List.of(new ScenarioFile.ProducerJson("it-load", "it-studio.x", List.of("k"),
                        500L, null, 256, null, null, null, null, null)),
                "1s", runFor, null, null);
    }
}
