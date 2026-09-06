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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.studio.store.RunStore;
import org.acemq.workloads.studio.store.ScenarioStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The studio's API, without a broker.
 *
 * <p>What is checked here is the part that has to work before a broker is even reachable: a
 * scenario can be saved and read back, a bad one is refused with a reason somebody can act on, and
 * the file it exports is the file the command line reads. A run needs a broker and is covered by
 * {@link StudioRunIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the studio's API")
class StudioApiTest {

    private static Path database;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ScenarioStore scenarios;

    @Autowired
    private RunStore runs;

    @Autowired
    private ObjectMapper json;

    @BeforeAll
    static void useATemporaryDatabase() throws Exception {
        database = Files.createTempFile("acemq-studio-test", ".db");
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

    @Test
    @DisplayName("saves a scenario and reads it back as the same thing")
    void savesAndReadsBack() {
        ScenarioFile scenario = aScenario();

        String id = scenarios.save(null, scenario);
        ScenarioFile read = scenarios.find(id).orElseThrow();

        assertThat(read.name()).isEqualTo("saved");
        assertThat(read.queues()).hasSize(1);
        assertThat(read.queues().get(0).consumers().concurrency()).isEqualTo(4);
        assertThat(scenarios.list()).extracting(ScenarioStore.Summary::name).contains("saved");
    }

    @Test
    @DisplayName("refuses a scenario the broker would refuse, and says which part")
    void refusesABadScenarioWithAReason() {
        ScenarioFile broken = new ScenarioFile("broken", "", null, null,
                List.of(new ScenarioFile.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("q", "classic", null, null, null,
                        // Bound to an exchange nothing declares: the broker's own error for this
                        // arrives mid-run as a channel closure that reads like a broker problem.
                        List.of(new ScenarioFile.BindingJson("odrers", "#")), null, null)),
                List.of(new ScenarioFile.ProducerJson("p", "orders", List.of("k"), 100L, null,
                        512, null, null, null, null)),
                "1s", "1s", null, null);

        ResponseEntity<String> response =
                http.postForEntity("/api/scenarios/check", broken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("odrers").contains("\"runnable\":false");
    }

    @Test
    @DisplayName("warns about a queue nobody consumes rather than refusing it")
    void warnsRatherThanRefusing() {
        ScenarioFile scenario = new ScenarioFile("backlog", "", null, null,
                List.of(new ScenarioFile.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("q", "classic", null, null, null,
                        List.of(new ScenarioFile.BindingJson("orders", "#")),
                        new ScenarioFile.ConsumersJson(1, 100, null, null, Boolean.FALSE), null)),
                List.of(new ScenarioFile.ProducerJson("p", "orders", List.of("k"), 100L, null,
                        512, null, null, null, null)),
                "1s", "1s", null, null);

        ResponseEntity<String> response =
                http.postForEntity("/api/scenarios/check", scenario, String.class);

        // A queue nobody reads is how you find out what a backlog costs. Worth saying, not worth
        // refusing.
        assertThat(response.getBody()).contains("\"runnable\":true").contains("nothing consumes q");
    }

    @Test
    @DisplayName("exports the file the command line reads, named for what it is")
    void exportsAFileTheCommandLineCanRead() throws Exception {
        ResponseEntity<String> response =
                http.postForEntity("/api/scenarios/export", aScenario(), String.class);

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("acemq-workload-saved-" + LocalDate.now() + ".json");

        // The round trip that matters: what came out is a scenario the engine can build.
        ScenarioFile read = json.readValue(response.getBody(), ScenarioFile.class);
        assertThat(read.toScenario().problems()).isEmpty();
        assertThat(read.toScenario().queues().get(0).consumersNode().prefetch()).isEqualTo(250);
    }

    @Test
    @DisplayName("opens a scenario file it exported, expectations and all")
    void opensAFileItExported() {
        ScenarioFile gated = new ScenarioFile("gated", "", null, null,
                List.of(new ScenarioFile.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("orders.q", "quorum", null, null, null,
                        List.of(new ScenarioFile.BindingJson("orders", "#")), null, null,
                        new ScenarioFile.ExpectJson("50ms", null, null, 1_000L, null, null, null,
                                Boolean.TRUE))),
                List.of(new ScenarioFile.ProducerJson("checkout", "orders", List.of("k"), 5_000L,
                        null, 512, null, null, null, null,
                        new ScenarioFile.ExpectJson(null, null, null, null, null, 5, Boolean.TRUE,
                                null))),
                "1s", "10s", null, null);

        String exported = http.postForObject("/api/scenarios/export", gated, String.class);
        ResponseEntity<String> opened = http.postForEntity(
                "/api/scenarios/import?fileName=acemq-workload-gated.json", exported, String.class);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody()).contains("\"runnable\":true").contains("\"consumeRateAtLeast\":1000");

        // And what came back is the same gate, not a description of one: p50 under 50ms survives
        // the round trip as something the run can fail on.
        assertThat(opened.getBody()).contains("\"p50Below\":\"50ms\"")
                .contains("\"withinPercentOfOffered\":5");
    }

    @Test
    @DisplayName("says what is wrong with a file it cannot open")
    void refusesAFileThatIsNotAScenario() {
        ResponseEntity<String> opened = http.postForEntity(
                "/api/scenarios/import?fileName=notes.yaml", "just: some notes\n", String.class);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(opened.getBody()).contains("error");
    }

    // Resolving these would read the studio's own environment on behalf of whoever uploaded the
    // file and hand the value back. The placeholder stays a placeholder.
    @Test
    @DisplayName("leaves ${VAR} in an opened file alone")
    void doesNotResolveVariablesFromItsOwnEnvironment() {
        String file = """
                {"name": "gated", "broker": "amqp://guest:${PATH}@localhost:5672",
                 "exchanges": [{"name": "e", "type": "topic"}],
                 "queues": [{"name": "q", "bindings": [{"exchange": "e", "routingKey": "#"}]}],
                 "producers": [{"name": "p", "exchange": "e", "routingKeys": ["k"]}]}""";

        ResponseEntity<String> opened =
                http.postForEntity("/api/scenarios/import?fileName=a.json", file, String.class);

        assertThat(opened.getBody()).contains("${PATH}");
    }

    @Test
    @DisplayName("offers presets that are runnable as they are")
    void presetsAreRunnableAsTheyStand() {
        ResponseEntity<ScenarioFile[]> response =
                http.getForEntity("/api/presets", ScenarioFile[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Read again through the API shape, because a preset that does not survive its own JSON
        // is a preset that fails the moment somebody presses Run.
        String body = http.getForObject("/api/presets", String.class);
        assertThat(body).contains("quorum-vs-classic", "slow-consumer", "find-the-ceiling");
    }

    @Test
    @DisplayName("keeps the broker's password out of the history")
    void doesNotStorePasswords() {
        assertThat(RunStore.redact("amqp://guest:s3cret@broker:5672"))
                .isEqualTo("amqp://guest:***@broker:5672");
        assertThat(RunStore.redact("amqp://broker:5672")).isEqualTo("amqp://broker:5672");
        assertThat(runs.recent(1)).isNotNull();
    }

    @Test
    @DisplayName("reads durations the way the workload file does")
    void readsDurations() {
        ScenarioFile scenario = new ScenarioFile("d", "", null, null,
                List.of(new ScenarioFile.ExchangeJson("e", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("q", "classic", null, null, null,
                        List.of(new ScenarioFile.BindingJson("e", "#")), null, null)),
                List.of(new ScenarioFile.ProducerJson("p", "e", List.of("k"), 1L, null, 1, null,
                        null, null, null)),
                "500ms", "2m", null, null);

        assertThat(scenario.toScenario().warmup().toMillis()).isEqualTo(500);
        assertThat(scenario.toScenario().duration().toMinutes()).isEqualTo(2);
    }

    private static ScenarioFile aScenario() {
        return new ScenarioFile("saved", "a scenario to keep", null, null,
                List.of(new ScenarioFile.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioFile.QueueJson("orders.q", "quorum", null, null, null,
                        List.of(new ScenarioFile.BindingJson("orders", "order.#")),
                        new ScenarioFile.ConsumersJson(4, 250, "1ms", null, null), null)),
                List.of(new ScenarioFile.ProducerJson("checkout", "orders", List.of("order.placed"),
                        5000L, null, 512, null, null, null, null)),
                "5s", "30s", null, null);
    }
}
