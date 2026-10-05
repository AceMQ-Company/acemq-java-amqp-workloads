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
package org.acemq.workloads.endurance;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;

/**
 * The leak drill: standing loads in every language, and every connection torn out from under all
 * of them, over and over, while the run watches what each process is holding rather than what it
 * is publishing.
 *
 * <p>Repetition rather than hours. What finds a leak is the number of times the leaking path runs,
 * and in an AMQP client that path is connection recovery: a channel not closed, a consumer tag
 * not forgotten, a reader thread left parked. A day of calm publishing runs it approximately
 * never; this runs it every cycle.
 *
 * <p>The run: start or adopt the loads, warm up, take baseline readings, close every connection
 * once a cycle and read on a timer, cool down, take final readings, and judge with
 * {@link Verdict}. The report and readings are written as {@code scripts/soak.sh} wrote them, plus
 * a JSON report for the console.
 */
public final class Endurance {

    /** The repeated recovery fault, and the connection count that proves it did something. */
    public interface Fault extends AutoCloseable {
        /** Closes every client connection on the cluster. */
        void closeAll(String reason);

        /** @return client connections on the whole cluster, or -1 if they could not be counted */
        long connections();

        @Override
        default void close() {
        }
    }

    /** What the run is doing, for a console or a terminal. Every method may be ignored. */
    public interface Listener {
        default void say(String message) {
        }

        default void phase(String phase) {
        }

        default void cycle(int done, int of) {
        }

        default void reading(Reading reading) {
        }
    }

    /** What the run found and where it wrote it. */
    public record Result(String stamp, Verdict verdict, Path report, Path json, Path readings,
            List<Reading> all) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EnduranceConfig config;
    private final ProcessSampler sampler;
    private final Fault fault;
    private final ClientLauncher launcher;
    private final Listener listener;
    private final ObjectMapper json = new ObjectMapper();

    public Endurance(EnduranceConfig config, ProcessSampler sampler, Fault fault, Listener listener) {
        this.config = config;
        this.sampler = sampler;
        this.fault = fault;
        this.listener = listener;
        this.launcher = new ClientLauncher(config, listener::say);
    }

    /**
     * @return the fault through the management API: every connection the broker lists, closed.
     *     {@code rabbitmqctl close_all_connections --global} did the same from inside a container;
     *     the API needs no container and no node name, and reaches the whole cluster from one.
     */
    public static Fault managementFault(EnduranceConfig config) {
        RabbitAdmin admin = RabbitAdmin.connect(config.management, config.user(), config.password());
        return new Fault() {
            @Override
            public void closeAll(String reason) {
                List<ConnectionInfo> open;
                try {
                    open = admin.connections();
                } catch (RuntimeException e) {
                    return;
                }
                for (ConnectionInfo c : open) {
                    try {
                        admin.closeConnection(c.name(), reason);
                    } catch (RuntimeException e) {
                        // Closed on its own between the listing and the close: the point stands.
                    }
                }
            }

            @Override
            public long connections() {
                try {
                    return admin.connections().size();
                } catch (RuntimeException e) {
                    return -1;
                }
            }

            @Override
            public void close() {
                admin.close();
            }
        };
    }

    /**
     * Runs the soak. Interrupting the calling thread stops it: the loads it started are stopped
     * and the interrupt is rethrown.
     *
     * @return the verdict and where it was written
     * @throws ClientLauncher.LaunchException if a load could not be started
     */
    public Result run() throws ClientLauncher.LaunchException, InterruptedException, IOException {
        config.validate();
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        Path reports = config.reportPath();
        Path state = config.statePath();
        Files.createDirectories(reports);
        Files.createDirectories(state);
        Path tsv = state.resolve("soak-" + stamp + ".tsv");
        Path closes = state.resolve("soak-" + stamp + ".closes");
        Path report = reports.resolve("soak-" + stamp + ".md");
        Path jsonReport = reports.resolve("soak-" + stamp + ".json");
        Files.writeString(tsv, Reading.HEADER + "\n");
        Files.writeString(closes, "");

        listener.say("soak: " + config.cycles + " cycles, one every " + config.cycleSeconds
                + "s, sampling every " + config.sampleSeconds + "s");
        listener.say("that is " + config.cycles * config.cycleSeconds / 60
                + " minutes of cycling after a " + config.warmupSeconds + "s warm-up");

        listener.phase("starting");
        List<ClientLauncher.Client> clients = launcher.start();
        List<Reading> all = Collections.synchronizedList(new ArrayList<>());
        List<Long> afterClose = new ArrayList<>();
        int closed = 0;
        try {
            listener.phase("warmup");
            listener.say("warming up for " + config.warmupSeconds + "s");
            sleep(config.warmupSeconds);

            // Several readings rather than one: any single one can land in a garbage
            // collection, and the verdict takes the median.
            listener.phase("baseline");
            listener.say("baseline");
            for (int i = 0; i < config.readings; i++) {
                sample("baseline", clients, tsv, all);
                sleep(config.readingGapSeconds);
            }

            listener.phase("cycling");
            int sinceSample = 0;
            for (int i = 1; i <= config.cycles; i++) {
                fault.closeAll("soak");
                // Read now, at the one moment the answer is visible: clients reconnect in about a
                // second, so a timed reading almost always lands after recovery, and a soak whose
                // closes all silently failed would otherwise pass every bound.
                long after = fault.connections();
                afterClose.add(after);
                Files.writeString(closes, after + "\n", StandardOpenOption.APPEND);
                closed++;
                listener.cycle(i, config.cycles);
                sleep(config.cycleSeconds);
                sinceSample += config.cycleSeconds;
                if (sinceSample >= config.sampleSeconds) {
                    sample("cycling", clients, tsv, all);
                    sinceSample = 0;
                }
            }

            listener.phase("cooldown");
            listener.say("cooling down for " + config.cooldownSeconds + "s");
            sleep(config.cooldownSeconds);

            listener.phase("final");
            listener.say("final readings");
            for (int i = 0; i < config.readings; i++) {
                sample("final", clients, tsv, all);
                sleep(config.readingGapSeconds);
            }
        } finally {
            if (config.keep) {
                listener.say("--keep: the loads are still running");
            } else if (clients.stream().anyMatch(ClientLauncher.Client::launched)) {
                listener.say("stopping the loads");
                launcher.stop(clients);
            }
        }

        listener.phase("verdict");
        List<Reading> readings = List.copyOf(all);
        Verdict verdict = Verdict.of(readings, afterClose, closed, config.allowances,
                config.knownUpstream);
        Path workspace = config.workspacePath();
        String readingsRel = tsv.startsWith(workspace) ? workspace.relativize(tsv).toString() : tsv.toString();
        String reportRel = report.startsWith(workspace) ? workspace.relativize(report).toString() : report.toString();
        Files.writeString(report, verdict.markdown(stamp, closed, config.cycleSeconds,
                config.sampleSeconds, config.allowances, readingsRel));
        Map<String, Object> doc = verdict.json(stamp, closed, config, reportRel, readingsRel, readings);
        json.writerWithDefaultPrettyPrinter().writeValue(jsonReport.toFile(), doc);
        listener.phase("done");
        return new Result(stamp, verdict, report, jsonReport, tsv, readings);
    }

    private void sample(String phase, List<ClientLauncher.Client> clients, Path tsv, List<Reading> all)
            throws IOException {
        long now = System.currentTimeMillis() / 1000;
        long conns = fault.connections();
        StringBuilder lines = new StringBuilder();
        for (ClientLauncher.Client c : clients) {
            Optional<ProcessSampler.Usage> usage = sampler.sample(c.pid());
            Reading r;
            if (usage.isEmpty()) {
                r = new Reading(now, phase, c.name(), 0, 0, 0, -1, -1, conns);
            } else {
                long[] counters = counters(c.samples());
                ProcessSampler.Usage u = usage.get();
                r = new Reading(now, phase, c.name(), u.rssKb(), u.fds(), u.threads(),
                        counters[0], counters[1], conns);
            }
            all.add(r);
            listener.reading(r);
            lines.append(r.tsv()).append('\n');
        }
        Files.writeString(tsv, lines, StandardOpenOption.APPEND);
    }

    /**
     * The counters the load itself writes, from the last readable lines of its sample file. A
     * partly written final line is normal when a file is read mid-write.
     *
     * @return {published, consumed}, -1 for either that no line carried
     */
    long[] counters(Path samples) {
        long published = -1;
        long consumed = -1;
        for (String line : tail(samples, 50)) {
            String t = line.strip();
            if (!t.startsWith("{")) {
                continue;
            }
            try {
                JsonNode n = json.readTree(t);
                published = n.has("published") ? n.get("published").asLong() : published;
                consumed = n.has("consumed") ? n.get("consumed").asLong() : consumed;
            } catch (IOException e) {
                // half a line
            }
        }
        return new long[] {published, consumed};
    }

    private static List<String> tail(Path file, int lines) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(0, len - 64 * 1024);
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            List<String> all = List.of(new String(buf, StandardCharsets.UTF_8).split("\n"));
            return all.subList(Math.max(0, all.size() - lines), all.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void sleep(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }
}
