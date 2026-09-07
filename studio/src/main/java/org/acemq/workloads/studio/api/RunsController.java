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

import org.acemq.workloads.studio.net.BrokerReachability;
import org.acemq.workloads.studio.run.Runs;
import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.studio.store.RunStore;
import org.acemq.workloads.studio.StudioProperties;
import org.acemq.workloads.studio.tls.TlsSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
     * The report as a file to keep.
     *
     * <p>A run watched in the studio and then described from memory in a ticket is a run nobody
     * can check. This is the same document the command line writes, named for the run.
     *
     * @param id a run
     * @param format json, html or md
     * @return the file
     */
    @GetMapping("/{id}/report.{format}")
    public ResponseEntity<String> download(@PathVariable String id, @PathVariable String format) {
        String extension = switch (format) {
            case "html" -> "html";
            case "md", "markdown" -> "md";
            case "json" -> "json";
            default -> null;
        };
        if (extension == null) {
            return ResponseEntity.badRequest()
                    .body("{\"error\": \"a report is json, html or md\"}");
        }

        return store.report(id, extension)
                .map(body -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + fileName(id, extension) + "\"")
                        .contentType(mediaType(extension))
                        .body(body))
                // A run recorded before this version kept only the JSON, and a bare 404 would
                // read as a missing run rather than a missing form of one.
                .orElseGet(() -> store.report(id).isPresent()
                        ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\": \"this run was recorded before the studio kept"
                                        + " reports as " + extension + "; its JSON is still here\"}")
                        : ResponseEntity.notFound().build());
    }

    /**
     * @param id the run
     * @param extension what kind of file
     * @return what to call it: the scenario and the day, the way an exported scenario is named
     */
    private String fileName(String id, String extension) {
        String name = store.recent(500).stream()
                .filter(run -> run.id().equals(id))
                .map(RunStore.Summary::scenarioName)
                .findFirst()
                .orElse("run");
        return "acemq-report-" + name.replaceAll("[^A-Za-z0-9._-]", "-") + "-"
                + LocalDate.now() + "." + extension;
    }

    private static MediaType mediaType(String extension) {
        return switch (extension) {
            case "html" -> MediaType.TEXT_HTML;
            case "md" -> MediaType.parseMediaType("text/markdown");
            default -> MediaType.APPLICATION_JSON;
        };
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
