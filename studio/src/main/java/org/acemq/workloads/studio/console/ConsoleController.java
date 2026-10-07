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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.Sample;
import org.acemq.workloads.cli.ConfigException;
import org.acemq.workloads.cli.WorkloadFile;
import org.acemq.workloads.endurance.EnduranceConfig;
import org.acemq.workloads.studio.StudioProperties;
import org.acemq.workloads.studio.net.BrokerReachability;
import org.acemq.workloads.studio.store.RunStore;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The workloads console: the studio's one interface, served at {@code /}. Four of its views are
 * backed here; scenarios, scenario runs, reports and the broker use the studio's own
 * {@code /api/scenarios}, {@code /api/runs}, {@code /api/presets} and {@code /api/broker}.
 *
 * <ul>
 *   <li><b>Standing loads</b> — the drill workspace's sample files, one per language, and the
 *       load started here, if any.
 *   <li><b>Load designer</b> — the form written as a workload file, checked by the library's
 *       parser, saved inside the workloads directory, and started on a click.
 *   <li><b>Delivery evidence</b> — the library's rules on each run started here.
 *   <li><b>Endurance</b> — the newest soak report in the drill workspace, as the library's
 *       {@code endurance} command wrote it (JSON), or as the old script did (markdown); and a soak
 *       started here, with its progress.
 * </ul>
 */
@Controller
public class ConsoleController {

    /** A file name, nothing else: no separator, no leading dot, a YAML extension. */
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}\\.ya?ml");

    private final StudioProperties properties;
    private final ConsoleRuns runs;
    private final BrokerReachability reachability;
    private final ObjectMapper json;
    private final EnduranceRuns soaks;

    public ConsoleController(StudioProperties properties, ConsoleRuns runs,
            BrokerReachability reachability, ObjectMapper json, EnduranceRuns soaks) {
        this.properties = properties;
        this.runs = runs;
        this.reachability = reachability;
        this.json = json;
        this.soaks = soaks;
    }

    /**
     * The studio's one interface. {@code /} is its home and {@code /console} stays an alias, so a
     * bookmark from before the two interfaces became one still opens it.
     *
     * @return the page
     */
    @GetMapping({"/", "/console", "/console/"})
    public String page() {
        return "forward:/console/index.html";
    }

    @GetMapping("/api/console/overview")
    @ResponseBody
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workloadsDir", properties.workloadsDir().toString());
        Path ws = properties.drillWorkspace();
        out.put("drillWorkspace", ws == null ? null : ws.toString());
        out.put("running", runs.isRunning() || soaks.isRunning());
        return out;
    }

    // ------------------------------------------------------------------ standing loads

    @GetMapping("/api/console/loads")
    @ResponseBody
    public Map<String, Object> loads() throws IOException {
        List<Map<String, Object>> clients = new ArrayList<>();
        List<Object> events = new ArrayList<>();
        Path ws = properties.drillWorkspace();
        if (ws != null) {
            for (Path file : DrillFiles.sampleFiles(ws)) {
                Map<String, Object> c = DrillFiles.client(file, json);
                events.addAll((List<?>) c.remove("events"));
                clients.add(c);
            }
        }
        runs.latest().ifPresent(live -> {
            Map<String, Object> c = studioClient(live);
            events.addAll((List<?>) c.remove("events"));
            clients.add(0, c);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drillWorkspace", ws == null ? null : ws.toString());
        out.put("clients", clients);
        out.put("events", events);
        return out;
    }

    private static Map<String, Object> studioClient(ConsoleRuns.Live live) {
        List<Sample> samples = live.samples();
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        boolean going = "starting".equals(live.state) || "running".equals(live.state);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "studio:" + live.id);
        c.put("runId", live.id);
        c.put("name", live.name);
        c.put("source", "studio");
        c.put("broker", live.broker);
        c.put("live", going);
        c.put("state", !going ? live.state
                : last != null && last.blocked() ? "blocked"
                : last == null ? "starting" : "moving");
        c.put("ageSeconds", 0);
        if (last != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", last.at().toString());
            m.put("published", last.published());
            m.put("confirmed", last.confirmed());
            m.put("consumed", last.consumed());
            m.put("refused", last.refused());
            m.put("failed", last.failed());
            m.put("blocked", last.blocked());
            if (last.queueDepth() != null) {
                m.put("queueDepth", last.queueDepth());
            }
            m.put("phase", last.phase().name());
            c.put("last", m);
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = Math.max(1, samples.size() - 60); i < samples.size(); i++) {
            Sample prev = samples.get(i - 1);
            Sample cur = samples.get(i);
            double dt = (cur.elapsed().toMillis() - prev.elapsed().toMillis()) / 1000.0;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("at", cur.at().toString());
            p.put("offered", DrillFiles.round(cur.publishRate()));
            p.put("confirmed", dt <= 0 ? 0
                    : DrillFiles.round(Math.max(0, cur.confirmed() - prev.confirmed()) / dt));
            p.put("consumed", DrillFiles.round(cur.consumeRate()));
            if (!cur.endToEnd().isEmpty()) {
                p.put("p99Ms", cur.endToEnd().p99().toNanos() / 1_000_000.0);
            }
            history.add(p);
        }
        c.put("history", history);
        c.put("latency", last == null ? null : Evidence.latency(last.endToEnd()));
        List<Object> events = new ArrayList<>();
        events.add(DrillFiles.event(live.started.toString(), "i", live.name,
                "started from the console against " + live.broker));
        if (last != null) {
            events.add(DrillFiles.event(last.at().toString(), "i", live.name,
                    "phase " + last.phase().name().toLowerCase(java.util.Locale.ROOT)));
        }
        if ("finished".equals(live.state)) {
            events.add(DrillFiles.event(null, "ok", live.name,
                    "finished · the evidence is in Delivery evidence"));
        }
        if (live.error != null) {
            events.add(DrillFiles.event(null, "e", live.name, live.error));
        }
        c.put("events", events);
        return c;
    }

    // ------------------------------------------------------------------ designer

    @GetMapping("/api/console/designer/defaults")
    @ResponseBody
    public WorkloadYaml.Form defaults() {
        return WorkloadYaml.defaults();
    }

    @PostMapping("/api/console/designer/yaml")
    @ResponseBody
    public Map<String, Object> yaml(@RequestBody WorkloadYaml.Form form) {
        String yaml = WorkloadYaml.toYaml(form);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaml", yaml);
        out.put("check", WorkloadYaml.check(yaml, System::getenv));
        return out;
    }

    /** A workload file, as text. */
    public record Text(String fileName, String yaml) {
    }

    @PostMapping("/api/console/designer/validate")
    @ResponseBody
    public WorkloadYaml.Check validate(@RequestBody Text body) {
        return WorkloadYaml.check(body.yaml(), System::getenv);
    }

    @GetMapping("/api/console/designer/files")
    @ResponseBody
    public List<Map<String, Object>> files() throws IOException {
        Path dir = properties.workloadsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> FILE.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .map(p -> {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("name", p.getFileName().toString());
                        try {
                            f.put("modified", Files.getLastModifiedTime(p).toString());
                        } catch (IOException e) {
                            f.put("modified", null);
                        }
                        return f;
                    })
                    .toList();
        }
    }

    @PostMapping("/api/console/designer/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@RequestBody Text body) throws IOException {
        Path dir = properties.workloadsDir().toAbsolutePath().normalize();
        String name = body.fileName() == null ? "" : body.fileName().strip();
        Path target = dir.resolve(name).normalize();
        if (!FILE.matcher(name).matches() || !dir.equals(target.getParent())) {
            return ResponseEntity.badRequest().body(Map.of("error", "'" + name + "' is not a"
                    + " file name the console writes: letters, digits, '.', '_' and '-', ending"
                    + " in .yaml or .yml, and nothing outside " + dir));
        }
        WorkloadYaml.Check check = WorkloadYaml.check(body.yaml(), System::getenv);
        if (!check.valid()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "not saved: the library does not accept this file",
                    "problems", check.problems()));
        }
        Files.createDirectories(dir);
        Files.writeString(target, body.yaml());
        return ResponseEntity.ok(Map.of("saved", target.toString()));
    }

    // ------------------------------------------------------------------ runs

    @PostMapping("/api/console/runs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> start(@RequestBody Text body) {
        String broker;
        try {
            WorkloadFile file = WorkloadFile.parseYaml(body.yaml(), System::getenv);
            broker = file.brokerUrl(0);
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
        BrokerReachability.Probe probe = reachability.probe(broker);
        if (!probe.isReachable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "the broker could not be reached",
                    "explanation", probe.explain()));
        }
        try {
            String id = runs.start(body.yaml(), probe.reachableUrl());
            return ResponseEntity.ok(Map.of("id", id, "explanation", probe.explain()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (ConfigException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/api/console/runs/{id}/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String id) {
        return runs.stop(id) ? ResponseEntity.ok(Map.of("stopping", true))
                : ResponseEntity.notFound().build();
    }

    // ------------------------------------------------------------------ evidence, endurance

    @GetMapping("/api/console/evidence")
    @ResponseBody
    public List<JsonNode> evidence() {
        return runs.evidence();
    }

    /**
     * The chaos framework's two process drills, newest result of each: the crash drill (kill -9
     * mid-flight, then count against the broker) and the claim lease (an idempotency claim left
     * by a killed process). Read from {@code ACEMQ_STUDIO_DRILL_REPORTS}, by default the drill
     * workspace's reports directory.
     */
    @GetMapping("/api/console/drills")
    @ResponseBody
    public Map<String, Object> drills() {
        Path dir = properties.drillReports();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir == null ? null : dir.toString());
        out.put("crash", DrillFiles.latestDrill(dir, "crash-drill"));
        out.put("claim", DrillFiles.latestDrill(dir, "claim-lease"));
        return out;
    }

    @GetMapping("/api/console/endurance")
    @ResponseBody
    public Map<String, Object> endurance() throws IOException {
        return DrillFiles.latestSoak(properties.drillWorkspace());
    }

    /** What the Start a soak form sends; anything left out is the soak's default. */
    public record SoakRequest(Integer cycles, Integer cycleSeconds, Integer warmupSeconds,
            Integer sampleSeconds, List<String> clients, String broker, String management) {
    }

    /**
     * Starts a soak, on a click and only then: one at a time, nothing else running, the drill
     * workspace configured, and both the broker and its management API answering first -- a soak
     * that discovers an unreachable broker after a two-minute warm-up has wasted the two minutes.
     */
    @PostMapping("/api/console/endurance/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startSoak(@RequestBody SoakRequest body) {
        Path ws = properties.drillWorkspace();
        if (ws == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "no drill workspace is"
                    + " configured. Set ACEMQ_STUDIO_DRILL_WORKSPACE to the AMQP libraries"
                    + " workspace; the soak starts its standing loads from there"));
        }
        EnduranceConfig config = new EnduranceConfig();
        config.workspace = ws.toString();
        if (body.cycles() != null) {
            config.cycles = body.cycles();
        }
        if (body.cycleSeconds() != null) {
            config.cycleSeconds = body.cycleSeconds();
        }
        if (body.warmupSeconds() != null) {
            config.warmupSeconds = body.warmupSeconds();
        }
        if (body.sampleSeconds() != null) {
            config.sampleSeconds = body.sampleSeconds();
        }
        if (body.clients() != null && !body.clients().isEmpty()) {
            config.clients = new ArrayList<>(body.clients());
        }
        if (body.broker() != null && !body.broker().isBlank()) {
            config.broker = body.broker().strip();
        }
        if (body.management() != null && !body.management().isBlank()) {
            config.management = body.management().strip();
        }
        try {
            config.validate();
        } catch (ConfigException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        for (String url : List.of(config.broker, config.management)) {
            BrokerReachability.Probe probe = reachability.probe(url);
            if (!probe.isReachable()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "the broker could not be reached at " + RunStore.redact(url),
                        "explanation", probe.explain()));
            }
        }
        try {
            soaks.start(config);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage())));
        }
        return ResponseEntity.ok(Map.of("started", true, "cycles", config.cycles));
    }

    @PostMapping("/api/console/endurance/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stopSoak() {
        return soaks.stop() ? ResponseEntity.ok(Map.of("stopping", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/console/endurance/live")
    @ResponseBody
    public Map<String, Object> soakProgress() {
        return soaks.progress();
    }
}
