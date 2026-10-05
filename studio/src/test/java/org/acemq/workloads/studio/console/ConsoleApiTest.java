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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The workloads console without a broker: every page and asset is served, the designer's file is
 * the library's, a save never leaves the workloads directory, and the drill workspace's files are
 * read as they are. Starting a run needs a broker and is {@link ConsoleRunIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the workloads console's API")
class ConsoleApiTest {

    private static final Path root;

    static {
        try {
            root = Files.createTempDirectory("acemq-console-test");
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
        registry.add("acemq.studio.database", () -> root.resolve("studio.db").toString());
        registry.add("acemq.studio.workloads-dir", () -> root.resolve("workloads").toString());
        registry.add("acemq.studio.drill-workspace", () -> root.resolve("workspace").toString());
    }

    @BeforeAll
    static void aDrillWorkspace() throws Exception {
        Path chaos = Files.createDirectories(root.resolve("workspace/.chaos"));
        Files.writeString(chaos.resolve("workload-go.jsonl"), String.join("\n",
                "{\"at\":\"2026-10-05T12:40:24Z\",\"elapsedMs\":1000,\"blocked\":false,\"published\":500,"
                        + "\"confirmed\":490,\"consumed\":489,\"failed\":0,\"refused\":0,"
                        + "\"publishRate\":500.0,\"consumeRate\":499.0}",
                "connection lost: CONNECTION_FORCED - soak",
                "{\"at\":\"2026-10-05T12:40:25Z\",\"elapsedMs\":2000,\"blocked\":false,\"published\":1000,"
                        + "\"confirmed\":980,\"consumed\":979,\"failed\":3,\"refused\":7,"
                        + "\"publishRate\":500.0,\"consumeRate\":490.0,\"endToEndP99Ms\":4.5}",
                ""));
        Files.writeString(chaos.resolve("soak-20261005-113058.tsv"), String.join("\n",
                "epoch\tphase\tlang\trss_kb\tfds\tthreads\tpublished\tconsumed\tconnections",
                "1791200005\tbaseline\tgo\t20384\t14\t15\t1\t1\t6",
                "1791200065\tcycling\tgo\t21384\t14\t16\t2\t2\t2",
                "1791204008\tfinal\tgo\t22528\t14\t16\t3\t3\t6", ""));
        Path reports = Files.createDirectories(root.resolve("workspace/reports"));
        Files.writeString(reports.resolve("soak-20261005-113058.md"), """
                # Soak — 20261005-113058

                240 cycles, one every 15s, sampling every 30s. Every client lost every connection 240 times.

                **PASSED**

                | library | RSS before | RSS after | fds before | fds after | threads before | threads after | verdict |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | go | 20MB | 22MB | 14 | 14 | 15 | 16 | clean |

                connections while settled: low 6, high 6
                connections immediately after a close: low 2, high 6, over 240 cycles

                Allowances: descriptors +16, threads +16, resident memory 2x the baseline.
                Readings: `.chaos/soak-20261005-113058.tsv`
                """);
        // An older report, which must not be the one shown.
        Files.writeString(reports.resolve("soak-20261001-020910.md"), "# Soak\n\n**FAILED**\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/console", "/console/", "/console/index.html", "/console/console.css",
        "/console/console.js"})
    @DisplayName("serves every page and asset")
    void servesEveryAsset(String path) {
        ResponseEntity<String> r = http.getForEntity(path, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotBlank();
        MediaType type = r.getHeaders().getContentType();
        assertThat(type).isNotNull();
        if (path.endsWith(".css")) {
            assertThat(type.getSubtype()).isEqualTo("css");
        } else if (path.endsWith(".js")) {
            assertThat(type.getSubtype()).isEqualTo("javascript");
        } else {
            assertThat(type.getSubtype()).isEqualTo("html");
            assertThat(r.getBody()).contains("Standing loads", "Endurance", "Delivery evidence",
                    "Load designer", "console.css", "console.js");
        }
    }

    @Test
    @DisplayName("the designer's defaults become a file the library parses")
    void designerRoundTrip() throws Exception {
        JsonNode defaults = json.readTree(
                http.getForObject("/api/console/designer/defaults", String.class));
        JsonNode r = json.readTree(http.postForObject("/api/console/designer/yaml", defaults,
                String.class));

        assertThat(r.path("check").path("valid").asBoolean()).isTrue();
        assertThat(r.path("yaml").asText()).contains("name: standing-load", "rate: 1000");
        JsonNode valid = json.readTree(http.postForObject("/api/console/designer/validate",
                Map.of("yaml", r.path("yaml").asText()), String.class));
        assertThat(valid.path("valid").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.yaml", "../../etc/escape.yaml", "sub/dir.yaml", "/tmp/abs.yaml",
        ".hidden.yaml", "notes.txt", "..", "a\\b.yaml", ""})
    @DisplayName("refuses a file name that is not a plain name inside the workloads directory")
    void refusesTraversal(String name) {
        ResponseEntity<String> r = http.postForEntity("/api/console/designer/files",
                Map.of("fileName", name, "yaml", "name: x\nbroker: amqp://localhost\n"),
                String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(root.resolve("escape.yaml")).doesNotExist();
        assertThat(Path.of("/tmp/abs.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("saves a valid file inside the workloads directory, and refuses an invalid one")
    void saves() throws Exception {
        ResponseEntity<String> ok = http.postForEntity("/api/console/designer/files",
                Map.of("fileName", "orders.yaml", "yaml", "name: orders\nbroker: amqp://localhost\n"),
                String.class);
        ResponseEntity<String> bad = http.postForEntity("/api/console/designer/files",
                Map.of("fileName", "broken.yaml", "yaml", "name: x\nnope: 1\n"), String.class);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(root.resolve("workloads/orders.yaml")).hasContent("name: orders\nbroker: amqp://localhost\n");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(root.resolve("workloads/broken.yaml")).doesNotExist();
        assertThat(http.getForObject("/api/console/designer/files", String.class)).contains("orders.yaml");
    }

    @Test
    @DisplayName("refuses to start a file the library refuses, before reaching for a broker")
    void refusesAnInvalidRun() {
        ResponseEntity<String> r = http.postForEntity("/api/console/runs",
                Map.of("yaml", "name: x\npublishers:\n  rat: 1\n"), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("publishers.rat");
    }

    @Test
    @DisplayName("reads a standing load's sample lines, and its other output as events")
    void readsStandingLoads() throws Exception {
        JsonNode loads = json.readTree(http.getForObject("/api/console/loads", String.class));
        JsonNode go = loads.path("clients").get(0);

        assertThat(go.path("name").asText()).isEqualTo("go");
        assertThat(go.path("source").asText()).isEqualTo("drill");
        assertThat(go.path("last").path("refused").asLong()).isEqualTo(7);
        assertThat(go.path("last").path("failed").asLong()).isEqualTo(3);
        assertThat(go.path("history")).hasSize(1);
        assertThat(go.path("history").get(0).path("confirmed").asDouble()).isEqualTo(490.0);
        assertThat(go.path("latency").path("p99Ms").asDouble()).isEqualTo(4.5);
        assertThat(loads.path("events").toString()).contains("CONNECTION_FORCED");
    }

    @Test
    @DisplayName("reads the newest soak report and its readings")
    void readsTheNewestSoak() throws Exception {
        JsonNode soak = json.readTree(http.getForObject("/api/console/endurance", String.class));

        assertThat(soak.path("available").asBoolean()).isTrue();
        assertThat(soak.path("id").asText()).isEqualTo("20261005-113058");
        assertThat(soak.path("verdict").asText()).isEqualTo("PASSED");
        assertThat(soak.path("cycles").asLong()).isEqualTo(240);
        assertThat(soak.path("fdSlack").asLong()).isEqualTo(16);
        assertThat(soak.path("rssFactor").asDouble()).isEqualTo(2.0);
        assertThat(soak.path("clients")).hasSize(1);
        assertThat(soak.path("clients").get(0).path("threadsAfter").asText()).isEqualTo("16");
        assertThat(soak.path("series").path("go")).hasSize(3);
    }

    @Test
    @DisplayName("says why there is no soak rather than inventing one")
    void noWorkspaceNoSoak() throws Exception {
        Map<String, Object> none = DrillFiles.latestSoak(null);

        assertThat(none).containsEntry("available", false);
        assertThat((String) none.get("reason")).contains("ACEMQ_STUDIO_DRILL_WORKSPACE");
        assertThat(DrillFiles.latestSoak(root.resolve("empty"))).containsEntry("available", false);
    }

    @Test
    @DisplayName("has no evidence before a run, and an empty list rather than an error")
    void noEvidenceYet() {
        assertThat(http.getForObject("/api/console/evidence", List.class)).isEmpty();
    }
}
