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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Starts the standing loads, or adopts ones already running, reports on them, and stops them.
 * The one place that knows how a load is launched: {@code endurance} and {@code loads} both go
 * through it.
 *
 * <p>Each load leaves the two files {@code scripts/chaos-drill.sh workload up} always left:
 * {@code workload-<client>.pid} and {@code workload-<client>.jsonl} in the state directory, so
 * a chaos drill's client timeline, the console's Standing loads view and either command see a
 * load started by any of them as the load it is.
 */
public final class ClientLauncher {

    /** A load being watched: its pid, and whether this call started it. */
    public record Client(String name, long pid, boolean launched, Path samples) {
    }

    /**
     * What a load is doing right now.
     *
     * @param pid the live pid, or -1 when stopped
     * @param lastSampleAge since the last sample's {@code at}, or null when it has none
     * @param publishRate the last sample's, or NaN
     * @param consumeRate the last sample's, or NaN
     */
    public record Status(String name, boolean running, long pid, Duration lastSampleAge,
            double publishRate, double consumeRate, Path samples) {
    }

    /** A load could not be started or stopped. */
    public static final class LaunchException extends Exception {
        private static final long serialVersionUID = 1L;

        LaunchException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EnduranceConfig config;
    private final Consumer<String> say;

    public ClientLauncher(EnduranceConfig config, Consumer<String> say) {
        this.config = config;
        this.say = say;
    }

    Path pidfile(String client) {
        return config.statePath().resolve("workload-" + client + ".pid");
    }

    Path samples(String client) {
        return config.statePath().resolve("workload-" + client + ".jsonl");
    }

    /**
     * Starts every configured client that is not already running, adopts those that are, and
     * waits until each one has written its first sample.
     *
     * @return every configured client, running and sampling
     * @throws LaunchException naming every client that could not be built, could not be started,
     *     exited, or wrote no sample within {@code startupSeconds}, each with its last lines of
     *     output; whatever this call had started is stopped again
     */
    public List<Client> start() throws LaunchException, InterruptedException {
        List<Client> out = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            Files.createDirectories(config.statePath());
            for (String name : config.clients) {
                Optional<Long> running = runningPid(name);
                if (running.isPresent()) {
                    say.accept(name + ": already running (pid " + running.get() + ")");
                    out.add(new Client(name, running.get(), false, samples(name)));
                    continue;
                }
                try {
                    out.add(launch(name));
                } catch (LaunchException e) {
                    failed.add(e.getMessage());
                }
            }
            failed.addAll(awaitFirstSamples(out));
            if (!failed.isEmpty()) {
                throw new LaunchException(String.join("\n", failed));
            }
            return out;
        } catch (LaunchException | InterruptedException e) {
            stop(out);
            throw e;
        } catch (IOException e) {
            stop(out);
            throw new LaunchException("could not prepare " + config.statePath() + ": " + e.getMessage());
        }
    }

    /**
     * A sample rather than a live pid is what "up" means: a drill straight afterwards reads the
     * timeline, and a load that has not printed its first line yet reads as a client that could
     * not be read.
     *
     * @return one message per client that died or stayed silent past the deadline
     */
    private List<String> awaitFirstSamples(List<Client> clients) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.startupSeconds);
        List<Client> waiting = new ArrayList<>(clients);
        List<String> failed = new ArrayList<>();
        while (!waiting.isEmpty()) {
            for (var it = waiting.iterator(); it.hasNext();) {
                Client c = it.next();
                if (lastSample(c.samples()).isPresent()) {
                    say.accept(c.name() + ": running (pid " + c.pid() + "), readings in " + c.samples());
                    it.remove();
                } else if (!alive(c.pid())) {
                    failed.add(c.name() + ": the standing load exited before its first sample."
                            + " Its output:\n" + tail(c.samples(), 5));
                    it.remove();
                }
            }
            if (!waiting.isEmpty() && System.nanoTime() > deadline) {
                for (Client c : waiting) {
                    failed.add(c.name() + ": no sample within " + config.startupSeconds + "s (pid "
                            + c.pid() + " is still running). Its output:\n" + tail(c.samples(), 5));
                }
                break;
            }
            if (!waiting.isEmpty()) {
                Thread.sleep(200);
            }
        }
        return failed;
    }

    private Client launch(String name) throws LaunchException, IOException, InterruptedException {
        EnduranceConfig.Launch l = config.launchFor(name);
        File dir = new File(config.expand(l.dir != null ? l.dir : "${workspace}"));
        Path samples = samples(name);
        Files.writeString(samples, "");
        if (l.build != null && !l.build.isEmpty()) {
            say.accept(name + ": building");
            Path log = config.statePath().resolve("build-" + name + ".log");
            Process build;
            try {
                build = builder(l.build, l.env, dir).redirectOutput(log.toFile()).start();
            } catch (IOException e) {
                throw new LaunchException(name + ": could not run the build ("
                        + String.join(" ", expand(l.build)) + "): " + e.getMessage());
            }
            if (build.waitFor() != 0) {
                throw new LaunchException(name + ": the build failed (" + String.join(" ", expand(l.build))
                        + "). Its output:\n" + tail(log, 10));
            }
        }
        say.accept(name + ": starting a standing load against " + redact(config.broker));
        Process p;
        try {
            p = builder(l.command, l.env, dir)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(samples.toFile()))
                    .start();
        } catch (IOException e) {
            throw new LaunchException(name + ": could not start " + expand(l.command).get(0) + ": "
                    + e.getMessage());
        }
        Files.writeString(pidfile(name), p.pid() + "\n");
        return new Client(name, p.pid(), true, samples);
    }

    private ProcessBuilder builder(List<String> command, Map<String, String> env, File dir) {
        ProcessBuilder b = new ProcessBuilder(expand(command)).directory(dir)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        if (env != null) {
            env.forEach((k, v) -> b.environment().put(k, config.expand(v)));
        }
        return b;
    }

    private List<String> expand(List<String> command) {
        return command.stream().map(config::expand).toList();
    }

    /** @return the pid in the client's pid file, if that process is alive */
    Optional<Long> runningPid(String name) {
        try {
            long pid = Long.parseLong(Files.readString(pidfile(name)).strip());
            return alive(pid) ? Optional.of(pid) : Optional.empty();
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** @return each configured client's state, read from its pid file and sample file */
    public List<Status> status() {
        List<Status> out = new ArrayList<>();
        for (String name : config.clients) {
            Optional<Long> pid = runningPid(name);
            Optional<JsonNode> last = lastSample(samples(name));
            Duration age = last.map(n -> n.path("at").asText(null)).map(at -> {
                try {
                    return Duration.between(Instant.parse(at), Instant.now());
                } catch (RuntimeException e) {
                    return null;
                }
            }).orElse(null);
            out.add(new Status(name, pid.isPresent(), pid.orElse(-1L), age,
                    last.map(n -> n.path("publishRate").asDouble(Double.NaN)).orElse(Double.NaN),
                    last.map(n -> n.path("consumeRate").asDouble(Double.NaN)).orElse(Double.NaN),
                    samples(name)));
        }
        return out;
    }

    /**
     * Stops every configured client found by its pid file, whoever started it, and removes stale
     * pid files.
     *
     * @throws LaunchException if a load survived both signals; the rest are still stopped
     */
    public void stopAll() throws LaunchException {
        List<String> stuck = new ArrayList<>();
        for (String name : config.clients) {
            Path pidfile = pidfile(name);
            if (!Files.exists(pidfile)) {
                say.accept(name + ": nothing to stop");
                continue;
            }
            Optional<Long> pid = runningPid(name);
            if (pid.isEmpty()) {
                delete(pidfile);
                say.accept(name + ": nothing to stop (removed a stale pid file)");
            } else if (kill(name, pid.get())) {
                say.accept(name + ": stopped");
            } else {
                stuck.add(name + ": pid " + pid.get() + " would not stop. Kill it before starting"
                        + " another load, or the next one shares a timeline with it.");
            }
        }
        if (!stuck.isEmpty()) {
            throw new LaunchException(String.join("\n", stuck));
        }
    }

    /** Stops what this run started. Adopted loads are left alone; they were somebody else's. */
    public void stop(List<Client> clients) {
        for (Client c : clients) {
            if (c.launched()) {
                kill(c.name(), c.pid());
            }
        }
    }

    /**
     * A polite signal, ten seconds, then a kill, as {@code workload down} always did. Checked
     * rather than assumed: a load that survives and keeps writing shares its timeline with the
     * next one, and two clients' counters in one file make every delta in it meaningless.
     *
     * @return whether the process is gone; its pid file is removed only then
     */
    private boolean kill(String name, long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            ProcessHandle h = handle.get();
            h.destroy();
            try {
                h.onExit().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                h.destroyForcibly();
                try {
                    h.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception again) {
                    return false;
                }
            }
        }
        delete(pidfile(name));
        return true;
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A stale pid file is read as "not running" next time.
        }
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * @return the last whole JSON object in a sample file. Lines that are not one -- a banner, a
     *     warning, half a line read mid-write -- are skipped.
     */
    static Optional<JsonNode> lastSample(Path samples) {
        List<String> lines = tailLines(samples, 50);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String t = lines.get(i).strip();
            if (t.startsWith("{")) {
                try {
                    JsonNode n = JSON.readTree(t);
                    if (n.isObject()) {
                        return Optional.of(n);
                    }
                } catch (IOException e) {
                    // half a line
                }
            }
        }
        return Optional.empty();
    }

    /** @return up to the last {@code lines} lines, read from the last 64 KiB only */
    static List<String> tailLines(Path file, int lines) {
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

    private static String tail(Path file, int lines) {
        List<String> t = tailLines(file, lines);
        return t.isEmpty() || t.equals(List.of("")) ? "(nothing)" : String.join("\n", t);
    }

    static String redact(String url) {
        return url.replaceAll("//([^:/@]+):[^@]*@", "//$1:***@");
    }
}
