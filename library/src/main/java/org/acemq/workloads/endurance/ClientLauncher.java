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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Starts the standing loads, or adopts ones already running, and stops what it started.
 *
 * <p>Each load leaves the same two files {@code scripts/chaos-drill.sh workload up} leaves:
 * {@code workload-<client>.pid} and {@code workload-<client>.jsonl} in the state directory, so
 * {@code chaos-drill.sh workload down}, a chaos drill and the console's Standing loads view all
 * see a load started here as the load it is.
 */
public final class ClientLauncher {

    /** A load being watched: its pid, and whether this run started it (and so stops it). */
    public record Client(String name, long pid, boolean launched, Path samples) {
    }

    /** A load could not be started. */
    public static final class LaunchException extends Exception {
        private static final long serialVersionUID = 1L;

        LaunchException(String message) {
            super(message);
        }
    }

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
     * @return every configured client, running
     * @throws LaunchException if one could not be built or started, or exited straight away;
     *     whatever this call had started by then is stopped again
     */
    public List<Client> start() throws LaunchException, InterruptedException {
        List<Client> out = new ArrayList<>();
        try {
            Files.createDirectories(config.statePath());
            for (String name : config.clients) {
                Optional<Long> running = runningPid(name);
                if (running.isPresent()) {
                    say.accept(name + ": already running (pid " + running.get() + ")");
                    out.add(new Client(name, running.get(), false, samples(name)));
                    continue;
                }
                out.add(launch(name));
            }
            if (out.stream().anyMatch(Client::launched)) {
                // One wait for all of them: the question is whether a load stays up, and a
                // load that dies on a bad URL or a missing gem does it within seconds.
                Thread.sleep(config.startupSeconds * 1000L);
                for (Client c : out) {
                    if (c.launched() && !alive(c.pid())) {
                        throw new LaunchException(c.name() + ": the standing load exited"
                                + " immediately. Its output:\n" + tail(c.samples(), 5));
                    }
                }
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

    private Client launch(String name) throws LaunchException, IOException, InterruptedException {
        EnduranceConfig.Launch l = config.launchFor(name);
        File dir = new File(config.expand(l.dir != null ? l.dir : "${workspace}"));
        Path samples = samples(name);
        Files.writeString(samples, "");
        if (l.build != null && !l.build.isEmpty()) {
            say.accept(name + ": building");
            Path log = config.statePath().resolve("build-" + name + ".log");
            Process build = builder(l.build, l.env, dir)
                    .redirectOutput(log.toFile()).start();
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

    /**
     * Stops what this run started, as {@code workload down} does: a polite signal, ten seconds,
     * then a kill. Adopted loads are left alone; they were somebody else's.
     */
    public void stop(List<Client> clients) {
        for (Client c : clients) {
            if (!c.launched()) {
                continue;
            }
            ProcessHandle.of(c.pid()).ifPresent(h -> {
                h.destroy();
                try {
                    h.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    h.destroyForcibly();
                }
            });
            try {
                Files.deleteIfExists(pidfile(c.name()));
            } catch (IOException e) {
                // A stale pid file is read as "not running" next time.
            }
        }
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(nothing)";
        }
    }

    static String redact(String url) {
        return url.replaceAll("//([^:/@]+):[^@]*@", "//$1:***@");
    }
}
