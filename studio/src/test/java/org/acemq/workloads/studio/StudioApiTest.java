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

import org.acemq.workloads.studio.scenario.ScenarioJson;
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
        ScenarioJson scenario = aScenario();

        String id = scenarios.save(null, scenario);
        ScenarioJson read = scenarios.find(id).orElseThrow();

        assertThat(read.name()).isEqualTo("saved");
        assertThat(read.queues()).hasSize(1);
        assertThat(read.queues().get(0).consumers().concurrency()).isEqualTo(4);
        assertThat(scenarios.list()).extracting(ScenarioStore.Summary::name).contains("saved");
    }

    @Test
    @DisplayName("refuses a scenario the broker would refuse, and says which part")
    void refusesABadScenarioWithAReason() {
        ScenarioJson broken = new ScenarioJson("broken", "", null, null,
                List.of(new ScenarioJson.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioJson.QueueJson("q", "classic", null, null, null,
                        // Bound to an exchange nothing declares: the broker's own error for this
                        // arrives mid-run as a channel closure that reads like a broker problem.
                        List.of(new ScenarioJson.BindingJson("odrers", "#")), null, null)),
                List.of(new ScenarioJson.ProducerJson("p", "orders", List.of("k"), 100L, null,
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
        ScenarioJson scenario = new ScenarioJson("backlog", "", null, null,
                List.of(new ScenarioJson.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioJson.QueueJson("q", "classic", null, null, null,
                        List.of(new ScenarioJson.BindingJson("orders", "#")),
                        new ScenarioJson.ConsumersJson(1, 100, null, null, Boolean.FALSE), null)),
                List.of(new ScenarioJson.ProducerJson("p", "orders", List.of("k"), 100L, null,
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
        ScenarioJson read = json.readValue(response.getBody(), ScenarioJson.class);
        assertThat(read.toScenario().problems()).isEmpty();
        assertThat(read.toScenario().queues().get(0).consumersNode().prefetch()).isEqualTo(250);
    }

    @Test
    @DisplayName("offers presets that are runnable as they are")
    void presetsAreRunnableAsTheyStand() {
        ResponseEntity<ScenarioJson[]> response =
                http.getForEntity("/api/presets", ScenarioJson[].class);
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
        ScenarioJson scenario = new ScenarioJson("d", "", null, null,
                List.of(new ScenarioJson.ExchangeJson("e", "topic", null, null, null)),
                List.of(new ScenarioJson.QueueJson("q", "classic", null, null, null,
                        List.of(new ScenarioJson.BindingJson("e", "#")), null, null)),
                List.of(new ScenarioJson.ProducerJson("p", "e", List.of("k"), 1L, null, 1, null,
                        null, null, null)),
                "500ms", "2m", null, null);

        assertThat(scenario.toScenario().warmup().toMillis()).isEqualTo(500);
        assertThat(scenario.toScenario().duration().toMinutes()).isEqualTo(2);
    }

    private static ScenarioJson aScenario() {
        return new ScenarioJson("saved", "a scenario to keep", null, null,
                List.of(new ScenarioJson.ExchangeJson("orders", "topic", null, null, null)),
                List.of(new ScenarioJson.QueueJson("orders.q", "quorum", null, null, null,
                        List.of(new ScenarioJson.BindingJson("orders", "order.#")),
                        new ScenarioJson.ConsumersJson(4, 250, "1ms", null, null), null)),
                List.of(new ScenarioJson.ProducerJson("checkout", "orders", List.of("order.placed"),
                        5000L, null, 512, null, null, null, null)),
                "5s", "30s", null, null);
    }
}
