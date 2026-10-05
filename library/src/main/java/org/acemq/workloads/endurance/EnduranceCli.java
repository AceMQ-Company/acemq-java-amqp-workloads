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
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;

import org.acemq.workloads.cli.ConfigException;

/**
 * {@code java -jar acemq-workload.jar endurance [options]}: the soak.
 *
 * <p>Exit codes are {@code scripts/soak.sh}'s, because that script now calls this and a release
 * gate reads them: 0 passed, 1 failed, 2 could not run.
 */
public final class EnduranceCli {

    public static final int PASSED = 0;
    public static final int FAILED = 1;
    public static final int COULD_NOT_RUN = 2;

    static final String USAGE = """
            acemq-workload endurance — the leak drill: standing loads in every language, every
            connection closed once a cycle, and what each client process holds read from outside

            usage:
              java -jar acemq-workload.jar endurance [-f endurance.yaml] [options]

            options:
              -f, --file <path>         endurance YAML; flags override it
                  --workspace <dir>     the AMQP libraries workspace (default: current directory);
                                        launch commands, reports/ and .chaos/ are relative to it
                  --cycles <n>          forced recoveries (240)
                  --cycle-seconds <n>   seconds between them (15)
                  --warmup <n>          seconds before the baseline (120)
                  --sample-seconds <n>  seconds between readings while cycling (30)
                  --cooldown <n>        seconds after the last cycle (60)
                  --clients <list>      comma separated (java,go,python,ruby,dotnet)
                  --broker <url>        the AMQP URL the loads use (amqp://guest:guest@localhost:5772)
                  --management <url>    the management API the fault uses (http://localhost:15772)
                  --report-dir <dir>    where soak-<stamp>.md and .json go (reports)
                  --keep                leave the loads this run started running
                  --quiet               summary and exit code only
              -h, --help                this

            writes reports/soak-<stamp>.md (what release-preflight.sh reads), soak-<stamp>.json
            next to it, and the raw readings in .chaos/soak-<stamp>.tsv.

            exit codes: 0 passed, 1 failed, 2 could not run
            """;

    private EnduranceCli() {
    }

    /**
     * @param args everything after {@code endurance}
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, ProcessSampler::forThisOs);
    }

    static int run(String[] args, PrintStream out, PrintStream err, Supplier<ProcessSampler> sampler) {
        EnduranceConfig config;
        boolean quiet;
        try {
            Parsed p = parse(args);
            if (p.help) {
                out.println(USAGE);
                return PASSED;
            }
            config = p.config;
            quiet = p.quiet;
            config.validate();
        } catch (ConfigException e) {
            err.println("endurance: " + e.getMessage());
            return COULD_NOT_RUN;
        }

        Endurance.Listener listener = new Endurance.Listener() {
            @Override
            public void say(String message) {
                if (!quiet) {
                    out.println("\033[36m==>\033[0m " + message);
                }
            }

            @Override
            public void cycle(int done, int of) {
                if (!quiet) {
                    out.print("\r    cycle " + done + "/" + of + (done == of ? "\n" : ""));
                    out.flush();
                }
            }
        };

        Thread main = Thread.currentThread();
        // Ctrl-C: interrupt the run and wait for it to stop the loads it started, rather than
        // leaving five publishers behind with nobody holding their pids.
        Thread hook = new Thread(() -> {
            main.interrupt();
            try {
                main.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try (Endurance.Fault fault = Endurance.managementFault(config)) {
            if (fault.connections() < 0) {
                err.println("endurance: the management API at " + config.management
                        + " did not answer; is the drill cluster up?");
                return COULD_NOT_RUN;
            }
            Endurance.Result r = new Endurance(config, sampler.get(), fault, listener).run();
            out.print(r.verdict().console());
            Path ws = config.workspacePath();
            out.println();
            out.println(r.verdict().status() + " — report: "
                    + (r.report().startsWith(ws) ? ws.relativize(r.report()) : r.report()));
            return r.verdict().passed() ? PASSED : FAILED;
        } catch (ClientLauncher.LaunchException e) {
            err.println("endurance: " + e.getMessage());
            return COULD_NOT_RUN;
        } catch (InterruptedException e) {
            err.println("endurance: stopped before the verdict; no report was written");
            return COULD_NOT_RUN;
        } catch (IOException | RuntimeException e) {
            err.println("endurance: " + e.getMessage());
            return COULD_NOT_RUN;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // already shutting down
            }
        }
    }

    record Parsed(EnduranceConfig config, boolean quiet, boolean help) {
    }

    static Parsed parse(String[] args) {
        Path file = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-f") || args[i].equals("--file")) {
                file = Path.of(value(args, ++i));
            }
        }
        EnduranceConfig c = EnduranceConfig.read(file);
        boolean quiet = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-f", "--file" -> i++;
                case "-h", "--help" -> help = true;
                case "--quiet" -> quiet = true;
                case "--keep" -> c.keep = true;
                case "--workspace" -> c.workspace = value(args, ++i);
                case "--cycles" -> c.cycles = number(args, ++i);
                case "--cycle-seconds" -> c.cycleSeconds = number(args, ++i);
                case "--warmup" -> c.warmupSeconds = number(args, ++i);
                case "--sample-seconds" -> c.sampleSeconds = number(args, ++i);
                case "--cooldown" -> c.cooldownSeconds = number(args, ++i);
                case "--clients" -> c.clients = new ArrayList<>(Arrays.stream(value(args, ++i).split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList());
                case "--broker" -> c.broker = value(args, ++i);
                case "--management" -> c.management = value(args, ++i);
                case "--report-dir" -> c.reportDir = value(args, ++i);
                default -> throw new ConfigException("unknown argument " + a);
            }
        }
        return new Parsed(c, quiet, help);
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new ConfigException(args[i - 1] + " needs a value");
        }
        return args[i];
    }

    private static int number(String[] args, int i) {
        String v = value(args, i);
        if (!v.matches("\\d+")) {
            throw new ConfigException(args[i - 1] + " must be a whole number, not '" + v + "'");
        }
        return Integer.parseInt(v);
    }
}
