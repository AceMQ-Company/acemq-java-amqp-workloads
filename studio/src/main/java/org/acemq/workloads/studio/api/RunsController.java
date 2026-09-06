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

import java.util.List;
import java.util.Map;

import org.acemq.workloads.studio.net.BrokerReachability;
import org.acemq.workloads.studio.run.Runs;
import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.studio.store.RunStore;
import org.acemq.workloads.studio.StudioProperties;
import org.acemq.workloads.studio.tls.TlsSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Starting runs, stopping them, watching them, and reading what they did. */
@RestController
@RequestMapping("/api/runs")
public class RunsController {

    private final Runs runs;
    private final RunStore store;
    private final BrokerReachability reachability;
    private final StudioProperties properties;

    public RunsController(Runs runs, RunStore store, BrokerReachability reachability,
            StudioProperties properties) {
        this.runs = runs;
        this.store = store;
        this.reachability = reachability;
        this.properties = properties;
    }

    /**
     * What to start.
     *
     * @param scenarioId the saved scenario, when it came from one
     * @param broker where to run it
     * @param scenario the scenario itself, so an unsaved design can be run
     */
    public record StartRequest(String scenarioId, String broker, ScenarioFile scenario,
            TlsSettings tls) {
    }

    /**
     * Starts a run.
     *
     * <p>The broker URL is resolved first. Inside a container the one somebody typed may name the
     * container rather than their machine, and starting a run against nothing produces a failure
     * that reads like the broker's fault.
     *
     * @param request what to run and where
     * @return the run's identifier, and what the URL was resolved to
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest request) {
        BrokerReachability.Probe probe = reachability.probe(request.broker());
        if (!probe.isReachable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "the broker could not be reached",
                    "explanation", probe.explain(),
                    "attempts", probe.attempts()));
        }

        try {
            String id = runs.start(request.scenarioId(), request.scenario(), probe.reachableUrl(),
                    request.tls(), properties.tlsWorkingDirectory());
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "broker", probe.reachableUrl(),
                    "rewritten", probe.wasRewritten(),
                    "explanation", probe.explain()));
        } catch (IllegalStateException e) {
            // A run is already going. 409 rather than 400: nothing about the request was wrong.
            return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Ends a run early. It still reports on the window it measured.
     *
     * @param id the run
     * @return whether there was one to stop
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String id) {
        return runs.stop(id)
                ? ResponseEntity.ok(Map.of("stopping", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * The live readings, as they are taken.
     *
     * <p>Server-sent events rather than a socket: this is one-way, it has to survive a reload, and
     * a browser reconnects to it on its own.
     *
     * @param id the run
     * @return the stream
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id) {
        return runs.watch(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @return the run that is going, if one is */
    @GetMapping("/current")
    public Map<String, Object> current() {
        return runs.current()
                .<Map<String, Object>>map(id -> Map.of("id", id, "running", true))
                .orElse(Map.of("running", false));
    }

    /**
     * @param limit how many
     * @return the most recent runs
     */
    @GetMapping
    public List<RunStore.Summary> recent(@RequestParam(defaultValue = "50") int limit) {
        return store.recent(Math.min(limit, 500));
    }

    /**
     * @param id a run
     * @return its report
     */
    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) {
        return store.report(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Every reading a finished run took, so it can be drawn again exactly as it was watched.
     *
     * @param id a run
     * @return the readings, in order
     */
    @GetMapping(value = "/{id}/samples", produces = MediaType.APPLICATION_JSON_VALUE)
    public String samples(@PathVariable String id) {
        return "[" + String.join(",", store.samples(id)) + "]";
    }
}
