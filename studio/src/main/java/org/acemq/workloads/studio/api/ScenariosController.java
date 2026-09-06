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
package org.acemq.workloads.studio.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.acemq.workloads.scenario.Scenario;
import org.acemq.workloads.studio.scenario.ScenarioJson;
import org.acemq.workloads.studio.store.ScenarioStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Saving, loading and checking scenarios. */
@RestController
@RequestMapping("/api/scenarios")
public class ScenariosController {

    private final ScenarioStore store;
    private final ObjectMapper json;
    private final ObjectMapper yaml;

    public ScenariosController(ScenarioStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
        this.yaml = new ObjectMapper(new YAMLFactory())
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    @GetMapping
    public List<ScenarioStore.Summary> list() {
        return store.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScenarioJson> get(@PathVariable String id) {
        return store.find(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Map<String, String> create(@RequestBody ScenarioJson scenario) {
        return Map.of("id", store.save(null, scenario));
    }

    @PutMapping("/{id}")
    public Map<String, String> update(@PathVariable String id, @RequestBody ScenarioJson scenario) {
        return Map.of("id", store.save(id, scenario));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return store.delete(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * What is wrong with a scenario, and what is merely worth knowing.
     *
     * <p>Called as the designer is used rather than when a run is started. Everything here fails
     * at the broker otherwise, in the middle of a run, as a channel closure that reads like a
     * broker problem.
     *
     * @param scenario the scenario as drawn
     * @return the problems and the warnings
     */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody ScenarioJson scenario) {
        try {
            Scenario built = scenario.toScenario();
            return Map.of(
                    "problems", built.problems(),
                    "warnings", built.warnings(),
                    "runnable", built.problems().isEmpty());
        } catch (RuntimeException e) {
            return Map.of(
                    "problems", List.of(String.valueOf(e.getMessage())),
                    "warnings", List.of(),
                    "runnable", false);
        }
    }

    /**
     * The scenario as a file to keep.
     *
     * <p>The same JSON the command line reads, which is the entire point of the designer: what
     * was drawn on a screen goes into a pipeline unchanged rather than being described to
     * somebody who then writes YAML by hand.
     *
     * @param scenario what to export
     * @return the file
     */
    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestBody ScenarioJson scenario) throws Exception {
        String body = json.writerWithDefaultPrettyPrinter().writeValueAsString(scenario);
        String fileName = ScenarioJson.fileName(scenario.name(), LocalDate.now());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * The same scenario as YAML, for a pipeline that already reads one.
     *
     * @param scenario what to export
     * @return the file
     */
    @PostMapping("/export.yaml")
    public ResponseEntity<String> exportYaml(@RequestBody ScenarioJson scenario) throws Exception {
        String body = yaml.writeValueAsString(scenario);
        String fileName = ScenarioJson.fileName(scenario.name(), LocalDate.now())
                .replace(".json", ".yaml");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(body);
    }
}
