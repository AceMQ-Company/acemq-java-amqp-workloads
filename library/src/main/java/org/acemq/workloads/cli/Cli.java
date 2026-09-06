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
package org.acemq.workloads.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.acemq.workloads.Workload;
import org.acemq.workloads.WorkloadReport;
import org.acemq.workloads.report.Reports;

/**
 * {@code java -jar acemq-workload.jar -f workload.yaml}
 *
 * <h2>Exit codes are the interface</h2>
 *
 * <p>More important than the report format, because a pipeline reads the exit code and a person
 * reads the report. The three failure modes are genuinely different and a build that treats them
 * alike will retry the one that can never succeed:
 *
 * <table>
 *   <caption>Exit codes</caption>
 *   <tr><td>0</td><td>every workload passed</td></tr>
 *   <tr><td>1</td><td>a run was sound and missed an objective — the broker's answer is "no"</td></tr>
 *   <tr><td>2</td><td>a run was <strong>invalid</strong>; nothing was measured, retrying as-is
 *       will produce the same non-answer</td></tr>
 *   <tr><td>3</td><td>the workload file is wrong</td></tr>
 *   <tr><td>4</td><td>the broker could not be reached, or the run failed outright</td></tr>
 * </table>
 */
public final class Cli {

    private static final String USAGE = """
            acemq-workload — a load generator for AMQP brokers

            usage:
              java -jar acemq-workload.jar -f <file> [options]

            options:
              -f, --file <path>       workload file, .yaml or .json (required)
                  --report <dir>      write reports into this directory
                  --format <list>     html, md, json (comma separated; default html,json)
                  --dry-run           resolve and print the configuration, run nothing
                  --quiet             only print the final verdict
              -h, --help              this
                  --version           version and exit

            exit codes:
              0  passed
              1  a sound run missed an objective
              2  a run was invalid: nothing was measured
              3  the workload file is wrong
              4  the broker could not be reached

            a workload file:
              name: orders-peak
              broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
              topology:   { exchange: orders, queue: orders.new, routingKey: order.created }
              publishers: { threads: 4, rate: 50000, messageSize: 1024 }
              consumers:  { concurrency: 8, prefetch: 100, handlerTime: 1ms }
              warmup: 10s
              runFor: 2m
              expect:
                throughputAtLeast: 45000
                p99Below: 50ms

            ${VAR} is read from the environment, so a password never has to live in a file
            that gets committed.
            """;

    static final int OK = 0;
    static final int OBJECTIVE_MISSED = 1;
    static final int INVALID_RUN = 2;
    static final int BAD_CONFIG = 3;
    static final int BROKER_UNREACHABLE = 4;

    private Cli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (ConfigException e) {
            err.println("acemq-workload: " + e.getMessage());
            err.println();
            err.println(USAGE);
            return BAD_CONFIG;
        }

        if (options.help) {
            out.println(USAGE);
            return OK;
        }
        if (options.version) {
            out.println("acemq-workload " + version());
            return OK;
        }

        WorkloadFile file;
        try {
            file = WorkloadFile.read(options.file);
        } catch (ConfigException e) {
            err.println("acemq-workload: " + e.getMessage());
            return BAD_CONFIG;
        }

        if (options.dryRun) {
            out.println(file.describe());
            return OK;
        }

        List<WorkloadReport> reports = new ArrayList<>();
        try {
            for (int i = 0; i < file.size(); i++) {
                Workload workload = file.workloads().get(i);
                if (!options.quiet) {
                    out.println("running " + workload.name() + " against "
                            + WorkloadFile.redact(file.brokerUrl(i)) + " ...");
                }
                WorkloadReport report = workload.run(file.brokerUrl(i));
                reports.add(report);
                if (!options.quiet) {
                    out.println(report.format());
                }
            }
        } catch (RuntimeException e) {
            err.println("acemq-workload: the run failed: " + e.getMessage());
            return BROKER_UNREACHABLE;
        }

        if (options.reportDir != null) {
            try {
                writeReports(reports, options, out);
            } catch (IOException e) {
                err.println("acemq-workload: could not write the report: " + e.getMessage());
                return BAD_CONFIG;
            }
        }

        boolean anyInvalid = reports.stream().anyMatch(r -> !r.isValid());
        boolean anyFailed = reports.stream().anyMatch(r -> !r.passed());

        if (anyInvalid) {
            out.println("INVALID — at least one run did not measure what it was asked to");
            return INVALID_RUN;
        }
        if (anyFailed) {
            out.println("FAILED — every run was sound and at least one objective was not met");
            return OBJECTIVE_MISSED;
        }
        out.println("PASSED — " + reports.size() + " workload" + (reports.size() == 1 ? "" : "s"));
        return OK;
    }

    private static void writeReports(List<WorkloadReport> reports, Options options, PrintStream out)
            throws IOException {
        Files.createDirectories(options.reportDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        for (String format : options.formats) {
            String body;
            String extension;
            switch (format) {
                case "html" -> {
                    body = Reports.toHtml(reports);
                    extension = "html";
                }
                case "md", "markdown" -> {
                    body = Reports.toMarkdown(reports);
                    extension = "md";
                }
                case "json" -> {
                    body = Reports.toJson(reports);
                    extension = "json";
                }
                default -> throw new ConfigException("unknown --format '" + format
                        + "'. Known: html, md, json."
                        + " PDF is deliberately absent: print the HTML from a browser, which"
                        + " produces a better document than this could and adds nothing to"
                        + " the build.");
            }
            Path target = options.reportDir.resolve("workload-" + stamp + "." + extension);
            Files.writeString(target, body);
            out.println("wrote " + target);
        }
    }

    private static String version() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "(from source)" : version;
    }

    /** Parsed arguments. Hand-rolled: the surface is small, and this keeps the jar free of
     *  a command-line library that consumers would then see in the dependency tree. */
    static final class Options {

        Path file;
        Path reportDir;
        Set<String> formats = new LinkedHashSet<>(List.of("html", "json"));
        boolean dryRun;
        boolean quiet;
        boolean help;
        boolean version;

        static Options parse(String[] args) {
            Options options = new Options();
            if (args.length == 0) {
                options.help = true;
                return options;
            }
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> options.help = true;
                    case "--version" -> options.version = true;
                    case "--dry-run" -> options.dryRun = true;
                    case "--quiet" -> options.quiet = true;
                    case "-f", "--file" -> options.file = Path.of(value(args, ++i, arg));
                    case "--report" -> options.reportDir = Path.of(value(args, ++i, arg));
                    case "--format" -> {
                        options.formats = new LinkedHashSet<>();
                        for (String format : value(args, ++i, arg).split(",")) {
                            options.formats.add(format.trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                    default -> throw new ConfigException("unknown option '" + arg + "'");
                }
            }
            if (!options.help && !options.version && options.file == null) {
                throw new ConfigException("a workload file is required: -f workload.yaml");
            }
            if (options.formats.contains("pdf")) {
                throw new ConfigException("PDF is deliberately not supported."
                        + " Generate HTML and print it from a browser: the result is better than"
                        + " a layout engine embedded here would produce, and it keeps a large"
                        + " dependency out of a tool whose output is a table and a list.");
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new ConfigException(option + " needs a value");
            }
            return args[index];
        }
    }
}
