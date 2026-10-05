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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.acemq.workloads.RunHandle;
import org.acemq.workloads.RunListener;
import org.acemq.workloads.Sample;
import org.acemq.workloads.Workload;
import org.acemq.workloads.WorkloadReport;
import org.acemq.workloads.cli.WorkloadFile;
import org.acemq.workloads.studio.StudioProperties;
import org.acemq.workloads.studio.run.Runs;
import org.acemq.workloads.studio.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Workload files started from the console, one at a time.
 *
 * <p>The run is the library's own {@link Workload#start}, given the file the designer wrote. Its
 * readings are kept in memory for the live view; when it ends, the rules are evaluated and the
 * evidence written as JSON under the workloads directory, so it survives a restart.
 */
@Service
public class ConsoleRuns {

    private static final Logger log = LoggerFactory.getLogger(ConsoleRuns.class);
    private static final int KEEP = 300;
    private static final DateTimeFormatter ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final StudioProperties properties;
    private final Runs scenarioRuns;
    private final ObjectMapper json;
    private volatile Live live;

    public ConsoleRuns(StudioProperties properties, @Lazy Runs scenarioRuns, ObjectMapper json) {
        this.properties = properties;
        this.scenarioRuns = scenarioRuns;
        this.json = json;
    }

    /** A run started here, while it goes and after it ends. */
    static final class Live {
        final String id;
        final String name;
        final String broker;
        final Instant started = Instant.now();
        final Deque<Sample> samples = new ArrayDeque<>();
        volatile RunHandle handle;
        volatile String state = "starting";
        volatile String error;

        Live(String id, String name, String broker) {
            this.id = id;
            this.name = name;
            this.broker = broker;
        }

        synchronized List<Sample> samples() {
            return new ArrayList<>(samples);
        }

        synchronized void add(Sample sample) {
            samples.addLast(sample);
            while (samples.size() > KEEP) {
                samples.removeFirst();
            }
        }
    }

    /** @return whether a run started here is still going */
    public boolean isRunning() {
        Live l = live;
        return l != null && ("starting".equals(l.state) || "running".equals(l.state));
    }

    Optional<Live> latest() {
        return Optional.ofNullable(live);
    }

    /**
     * @param yaml the workload file
     * @param brokerUrl where to run it, already resolved and reachable
     * @return the run's id
     */
    synchronized String start(String yaml, String brokerUrl) {
        if (isRunning()) {
            throw new IllegalStateException("a load is already running (" + live.name + ")."
                    + " Two load generators on one machine measure each other; stop it first");
        }
        if (scenarioRuns.current().isPresent()) {
            throw new IllegalStateException("a scenario run is going in the studio. Two load"
                    + " generators on one machine measure each other; stop it first");
        }
        WorkloadFile file = WorkloadFile.parseYaml(yaml, System::getenv);
        if (file.size() != 1) {
            throw new IllegalArgumentException("this file holds " + file.size() + " workloads;"
                    + " the console runs one at a time. Run a suite with acemq-workload.jar -f");
        }
        Workload workload = file.workloads().get(0);
        boolean noLost = expectsNoMessagesLost(yaml);
        String id = ID.format(Instant.now()) + "-" + workload.name().replaceAll("[^A-Za-z0-9._-]", "-");
        Live run = new Live(id, workload.name(), RunStore.redact(brokerUrl));
        live = run;
        run.handle = workload.start(brokerUrl, new RunListener() {
            @Override
            public void onSample(Sample sample) {
                run.state = "running";
                run.add(sample);
            }

            @Override
            public void onFinished(WorkloadReport report) {
                try {
                    write(run, report, yaml, noLost);
                    run.state = "finished";
                } catch (RuntimeException | IOException e) {
                    log.error("run {} finished but its evidence could not be written", id, e);
                    run.error = "the run finished but its evidence could not be written: "
                            + e.getMessage();
                    run.state = "failed";
                }
            }

            @Override
            public void onFailed(Throwable failure) {
                run.error = RunStore.redact(String.valueOf(failure.getMessage()));
                run.state = "failed";
            }
        });
        return id;
    }

    boolean stop(String id) {
        Live l = live;
        if (l == null || !l.id.equals(id) || l.handle == null) {
            return false;
        }
        l.handle.stop();
        return true;
    }

    @PreDestroy
    void stopOnShutdown() {
        Live l = live;
        if (l != null && l.handle != null && isRunning()) {
            l.handle.stop();
            try {
                l.handle.report().get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("the console's run did not stop in time; its evidence may be missing");
            }
        }
    }

    private void write(Live run, WorkloadReport report, String yaml, boolean noLost)
            throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.id);
        out.put("name", report.name());
        out.put("broker", run.broker);
        out.put("startedAt", report.startedAt().toString());
        out.put("durationMs", report.duration().toMillis());
        out.put("verdict", !report.isValid() ? "invalid" : report.passed() ? "passed" : "failed");
        out.put("published", report.published());
        out.put("confirmed", report.confirmed());
        out.put("consumed", report.consumed());
        out.put("failed", report.failed());
        List<Sample> samples = report.samples();
        out.put("refused", samples.isEmpty() ? 0 : samples.get(samples.size() - 1).refused());
        out.put("offeredRate", report.offeredRate());
        out.put("achievedPublishRate", Math.round(report.achievedPublishRate()));
        out.put("consumeRate", Math.round(report.consumeRate()));
        out.put("endToEnd", Evidence.latency(report.endToEnd()));
        out.put("rules", Evidence.of(report, noLost));
        out.put("yaml", yaml);
        Path dir = runsDir();
        Files.createDirectories(dir);
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(run.id + ".json").toFile(), out);
    }

    Path runsDir() {
        return properties.workloadsDir().resolve("runs");
    }

    /** @return the evidence of every run started here, newest first */
    List<JsonNode> evidence() {
        Path dir = runsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .limit(50)
                    .map(this::readQuietly)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Optional<JsonNode> readQuietly(Path p) {
        try {
            return Optional.of(json.readTree(p.toFile()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean expectsNoMessagesLost(String yaml) {
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
            return root != null && root.path("expect").path("noMessagesLost").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }
}
