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

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.cli.ConfigException;

/**
 * {@code java -jar acemq-workload.jar loads up|down|status}: the standing loads a chaos drill
 * watches, started, stopped and reported on through the same {@link ClientLauncher} the soak
 * uses. {@code scripts/chaos-drill.sh workload ...} is a thin call to this.
 */
public final class LoadsCli {

    public static final int OK = 0;
    public static final int COULD_NOT = 2;

    static final String USAGE = """
            acemq-workload loads — the standing loads a chaos drill watches

            usage:
              java -jar acemq-workload.jar loads up     [options]
              java -jar acemq-workload.jar loads down   [options]
              java -jar acemq-workload.jar loads status [options] [--json]

            options:
              -f, --file <path>         endurance YAML (launch.<client> overrides); flags override it
                  --workspace <dir>     the AMQP libraries workspace (default: current directory)
                  --broker <url>        the AMQP URL the loads use (amqp://guest:guest@localhost:5772)
                  --clients <list>      comma separated (java,go,python,ruby,dotnet)
                  --state <dir>         pid and sample files, relative to the workspace (.chaos)
                  --json                status only: machine-readable
              -h, --help                this

            up starts each client not already running (a live pid in <state>/workload-<client>.pid
            is adopted), appends its output to <state>/workload-<client>.jsonl, and returns once
            every one has written a sample. down stops each by its pid file: TERM, ten seconds,
            then KILL. status reads both files.

            exit codes: 0 done (down: also when nothing was running), 2 could not -- which client
            and why is on stderr
            """;

    private LoadsCli() {
    }

    /**
     * @param args everything after {@code loads}
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        String sub;
        EnduranceConfig config;
        boolean json = false;
        try {
            if (args.length == 0 || List.of("-h", "--help", "help").contains(args[0])) {
                out.println(USAGE);
                return OK;
            }
            sub = args[0];
            if (!List.of("up", "down", "status").contains(sub)) {
                throw new ConfigException("unknown loads command '" + sub + "'. Try: up, down, status");
            }
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            Path file = null;
            for (int i = 0; i < rest.length; i++) {
                if (rest[i].equals("-f") || rest[i].equals("--file")) {
                    file = Path.of(EnduranceCli.value(rest, ++i));
                }
            }
            config = EnduranceConfig.read(file);
            for (int i = 0; i < rest.length; i++) {
                switch (rest[i]) {
                    case "-f", "--file" -> i++;
                    case "-h", "--help" -> {
                        out.println(USAGE);
                        return OK;
                    }
                    case "--json" -> json = true;
                    case "--workspace" -> config.workspace = EnduranceCli.value(rest, ++i);
                    case "--broker" -> config.broker = EnduranceCli.value(rest, ++i);
                    case "--state" -> config.stateDir = EnduranceCli.value(rest, ++i);
                    case "--clients" -> config.clients = EnduranceCli.list(EnduranceCli.value(rest, ++i));
                    default -> throw new ConfigException("unknown argument " + rest[i]);
                }
            }
            if (sub.equals("up")) {
                config.validate();
            } else if (config.clients.isEmpty()) {
                throw new ConfigException("no clients");
            }
        } catch (ConfigException e) {
            err.println("loads: " + e.getMessage());
            return COULD_NOT;
        }

        ClientLauncher launcher = new ClientLauncher(config, m -> out.println("\033[36m==>\033[0m " + m));
        try {
            switch (sub) {
                case "up" -> {
                    // Ctrl-C while waiting: stop what this call started rather than orphan it.
                    Thread hook = EnduranceCli.interruptOnShutdown();
                    try {
                        launcher.start();
                    } finally {
                        EnduranceCli.removeHook(hook);
                    }
                }
                case "down" -> launcher.stopAll();
                default -> out.print(json ? json(config, launcher.status()) : table(launcher.status()));
            }
            return OK;
        } catch (ClientLauncher.LaunchException e) {
            err.println("loads " + sub + ": " + e.getMessage());
            return COULD_NOT;
        } catch (InterruptedException e) {
            err.println("loads up: interrupted; the loads this call started were stopped");
            return COULD_NOT;
        }
    }

    static String table(List<ClientLauncher.Status> all) {
        StringBuilder b = new StringBuilder(String.format("%-8s %-8s %-8s %-12s %10s %10s%n",
                "client", "state", "pid", "last sample", "publish/s", "consume/s"));
        for (ClientLauncher.Status s : all) {
            b.append(String.format("%-8s %-8s %-8s %-12s %10s %10s%n", s.name(),
                    s.running() ? "running" : "stopped", s.running() ? Long.toString(s.pid()) : "-",
                    s.lastSampleAge() == null ? "-"
                            : String.format("%.1fs ago", s.lastSampleAge().toMillis() / 1000.0),
                    rate(s.publishRate()), rate(s.consumeRate())));
        }
        return b.toString();
    }

    private static String rate(double r) {
        return Double.isNaN(r) ? "-" : String.format("%.1f", r);
    }

    static String json(EnduranceConfig config, List<ClientLauncher.Status> all) {
        List<Map<String, Object>> clients = new ArrayList<>();
        for (ClientLauncher.Status s : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client", s.name());
            m.put("running", s.running());
            m.put("pid", s.running() ? s.pid() : null);
            m.put("lastSampleAgeSeconds", s.lastSampleAge() == null ? null
                    : s.lastSampleAge().toMillis() / 1000.0);
            m.put("publishRate", Double.isNaN(s.publishRate()) ? null : s.publishRate());
            m.put("consumeRate", Double.isNaN(s.consumeRate()) ? null : s.consumeRate());
            m.put("samples", s.samples().toString());
            clients.add(m);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("state", config.statePath().toString());
        doc.put("clients", clients);
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
