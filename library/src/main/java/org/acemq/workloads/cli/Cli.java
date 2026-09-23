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
import org.acemq.workloads.report.ScenarioReports;
import org.acemq.workloads.scenario.Scenario;
import org.acemq.amqp.security.Security;
import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.scenario.ScenarioReader;
import org.acemq.workloads.scenario.ScenarioReport;
import org.acemq.workloads.scenario.ScenarioRunner;

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
 *   <tr><td>3</td><td>the workload file is wrong — including when it is the broker that says so,
 *       by refusing a topology that contradicts what it already has</td></tr>
 *   <tr><td>4</td><td>the broker could not be reached, or the run failed outright</td></tr>
 * </table>
 *
 * <p>A broker that answers and refuses is deliberately not 4. See {@link BrokerFailure}.
 *
 * <p>Everything this class prints goes out through {@link Redaction}, so no message can leak a
 * password by taking a route nobody remembered to redact.
 */
public final class Cli {

    private static final String USAGE = """
            acemq-workload — a load generator for AMQP brokers

            usage:
              java -jar acemq-workload.jar -f <file> [options]

            options:
              -f, --file <path>       workload or scenario file, .yaml or .json (required)
                  --broker <url>      the broker to run against, overriding the file
                  --tls <mode>        required, insecure or disabled, overriding the file
                  --truststore <path> keystore holding the CA to trust, and a client
                                      certificate if one is needed
                  --truststore-password <pw>
                  --allow-development-certificates
                                      accept a certificate the broker generated for itself
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
              3  the workload file is wrong, including when the broker is the one
                 saying so by refusing a topology it disagrees with
              4  the broker could not be reached: nobody answered

            a scenario file is what the studio exports, and runs here unchanged:
              name: monday-morning
              broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
              exchanges: [ { name: orders, type: topic } ]
              queues:
                - name: orders.shipping
                  type: quorum
                  bindings: [ { exchange: orders, routingKey: "order.*" } ]
                  consumers: { concurrency: 8, prefetch: 200 }
              producers:
                - { name: checkout, exchange: orders, routingKeys: [order.placed], rate: 20000 }
              warmup: 10s
              runFor: 2m

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

    /**
     * Runs the tool, with both streams wrapped so that nothing printed anywhere below here can
     * carry a password out to a terminal or a CI log. Doing it once, at the edge, is the point:
     * the leak this fixes was a single message that did not call the redaction everything else
     * called, and the next such message would have been just as easy to write.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        PrintStream safeOut = Redaction.wrap(out);
        PrintStream safeErr = Redaction.wrap(err);
        try {
            return execute(args, safeOut, safeErr);
        } finally {
            safeOut.flush();
            safeErr.flush();
        }
    }

    private static int execute(String[] args, PrintStream out, PrintStream err) {
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

        // What kind of file this is, decided by what is in it rather than by a flag
        // somebody has to remember. A scenario names producers, queues and exchanges;
        // a workload names a topology with publishers and consumers.
        if (ScenarioReader.isScenario(options.file)) {
            return runScenario(options, out, err);
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
            return reportRunFailure(e, err);
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

    /**
     * Runs a scenario file: the thing the studio exports.
     *
     * <p>The same exit codes as a workload, because a pipeline should not have to care which
     * kind of file it was given. A scenario that cannot be run at all -- a binding to an
     * exchange nothing declares -- is a bad file rather than a failed run, and says so before
     * anything touches the broker.
     */
    /**
     * The TLS policy for this run: the command line if it said anything, otherwise the file.
     *
     * <p>Returning null is meaningful and is the common case -- it leaves the decision to the
     * URL, which is plaintext for {@code amqp://} and the JVM's own trust store for
     * {@code amqps://}. A policy is only needed when neither of those is right: a private
     * certificate authority, a client certificate, or a broker that signed its own certificate.
     *
     * <p>The command line wins over the file rather than merging with it. Half the file's policy
     * and half the flags' is a third policy nobody wrote down, and the one time that matters is
     * the run where somebody meant to turn verification off for a development broker and turned
     * it off for production instead.
     */
    private static Security securityFor(Options options, ScenarioFile file) {
        boolean fromFlags = options.tlsMode != null || options.truststore != null
                || options.truststorePassword != null || options.allowDevelopmentCertificates;
        if (fromFlags) {
            return new ScenarioFile.SecurityJson(
                    options.tlsMode, options.truststore, options.truststorePassword,
                    options.allowDevelopmentCertificates ? Boolean.TRUE : null).toSecurity();
        }
        return file.security() == null ? null : file.security().toSecurity();
    }

    private static int runScenario(Options options, PrintStream out, PrintStream err) {
        ScenarioFile file;
        try {
            file = ScenarioReader.read(options.file);
        } catch (RuntimeException e) {
            err.println("acemq-workload: " + e.getMessage());
            return BAD_CONFIG;
        }

        if (options.dryRun) {
            out.println(ScenarioReader.describe(file));
            return OK;
        }

        Scenario scenario;
        List<String> problems;
        try {
            scenario = file.toScenario();
            problems = scenario.problems();
        } catch (RuntimeException e) {
            err.println("acemq-workload: " + e.getMessage());
            return BAD_CONFIG;
        }
        if (!problems.isEmpty()) {
            err.println("acemq-workload: this scenario cannot run:");
            for (String problem : problems) {
                err.println("  - " + problem);
            }
            return BAD_CONFIG;
        }

        Security security;
        try {
            security = securityFor(options, file);
        } catch (RuntimeException e) {
            err.println("acemq-workload: " + e.getMessage());
            return BAD_CONFIG;
        }

        String broker = options.broker != null ? options.broker : file.broker();
        if (broker == null || broker.isBlank()) {
            err.println("acemq-workload: no broker. Put one in the file as 'broker:', or pass"
                    + " --broker amqp://guest:guest@localhost:5672");
            return BAD_CONFIG;
        }

        if (!options.quiet) {
            out.println("running " + scenario.name() + " against "
                    + ScenarioReader.redact(broker) + " ...");
            for (String warning : scenario.warnings()) {
                out.println("  warning: " + warning);
            }
        }

        ScenarioReport report;
        try {
            report = ScenarioRunner.run(scenario, broker, security);
        } catch (RuntimeException e) {
            return reportRunFailure(e, err);
        }

        if (!options.quiet) {
            out.println(report.format());
        }

        if (options.reportDir != null) {
            try {
                writeScenarioReports(report, options, out);
            } catch (IOException e) {
                err.println("acemq-workload: could not write the report: " + e.getMessage());
                return BAD_CONFIG;
            }
        }

        if (!report.isValid()) {
            out.println("INVALID -- this run did not measure what it was asked to");
            return INVALID_RUN;
        }
        if (!report.passed()) {
            out.println("FAILED -- the run was sound and something it was asked for did not hold");
            return OBJECTIVE_MISSED;
        }
        out.println("PASSED -- " + scenario.name());
        return OK;
    }

    /**
     * Says which of the two things went wrong, and returns the exit code that says it.
     *
     * <p>A broker that answered and refused is not an unreachable broker. Redeclaring an
     * exchange under a different type comes back as a refusal from a broker that is up, healthy
     * and reachable, and reporting it as exit 4 sends the reader to check the network for a
     * mistake that is in their file. It gets exit 3, the code a misspelled setting gets, because
     * it is the same kind of problem: the file asks for something that cannot be had, and no
     * number of retries turns it into a pass.
     *
     * <p>Exit 4 keeps its meaning — nobody answered — so a pipeline that branches on it can go
     * on sending somebody to look at the firewall, the hostname and the container.
     */
    private static int reportRunFailure(RuntimeException failure, PrintStream err) {
        String refusal = BrokerFailure.refusal(failure);
        if (refusal == null) {
            err.println("acemq-workload: the run failed: " + failure.getMessage());
            return BROKER_UNREACHABLE;
        }
        err.println("acemq-workload: the broker refused this run: " + failure.getMessage());
        err.println("  the broker said: " + refusal);
        err.println("  It answered, so this is not a network problem. Something in the file"
                + " contradicts what the broker already has, and it will refuse again until"
                + " one of the two changes.");
        return BAD_CONFIG;
    }

    private static void writeScenarioReports(ScenarioReport report, Options options,
            PrintStream out) throws IOException {
        Files.createDirectories(options.reportDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        for (String format : options.formats) {
            String body;
            String extension;
            switch (format) {
                case "html" -> {
                    body = ScenarioReports.toHtml(report);
                    extension = "html";
                }
                case "md", "markdown" -> {
                    body = ScenarioReports.toMarkdown(report);
                    extension = "md";
                }
                case "json" -> {
                    body = ScenarioReports.toJson(report);
                    extension = "json";
                }
                default -> throw new ConfigException("unknown --format '" + format
                        + "'. Known: html, md, json.");
            }
            Path target = options.reportDir.resolve("scenario-" + stamp + "." + extension);
            Files.writeString(target, body);
            out.println("wrote " + target);
        }
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
        String broker;
        // TLS on the command line, because a URL cannot carry which certificates to believe
        // and a scenario file that is run against both staging and production should not have
        // to be edited to change that.
        String tlsMode;
        String truststore;
        String truststorePassword;
        boolean allowDevelopmentCertificates;
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
                    // The same file against staging and then production is the ordinary
                    // way this gets used, and editing the file in between is how the two
                    // stop being the same test.
                    case "--broker" -> options.broker = value(args, ++i, arg);
                    case "--tls" -> options.tlsMode = value(args, ++i, arg);
                    case "--truststore" -> options.truststore = value(args, ++i, arg);
                    case "--truststore-password" ->
                            options.truststorePassword = value(args, ++i, arg);
                    case "--allow-development-certificates" ->
                            options.allowDevelopmentCertificates = true;
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
