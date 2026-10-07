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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.acemq.workloads.cli.ConfigException;

/**
 * What an endurance run is asked to do: the soak's numbers, the clients to watch and how each one
 * is started, the allowances a leak has to cross, and the upstream defects allowed within a
 * measured ceiling.
 *
 * <p>Every default is what {@code scripts/soak.sh} used, so a run with no file and no flags is the
 * soak the release preflight has always read. A YAML file overrides any of it; flags override the
 * file.
 */
public final class EnduranceConfig {

    /** The five standing loads the workspace has, in the order the soak always listed them. */
    public static final List<String> LANGUAGES = List.of("java", "go", "python", "ruby", "dotnet");

    /** The workspace the default launch commands, reports and pid files are relative to. */
    public String workspace = ".";
    /** The AMQP endpoint every standing load publishes to. */
    public String broker = "amqp://guest:guest@localhost:5772";
    /** The management API the fault and the connection counts go through. */
    public String management = "http://localhost:15772";
    /** Management credentials; taken from the broker URL when not given. */
    public String managementUser;
    public String managementPassword;

    public int cycles = 240;
    public int cycleSeconds = 15;
    public int warmupSeconds = 120;
    public int sampleSeconds = 30;
    /** After the last cycle: a client still mid-recovery holds two of some things. */
    public int cooldownSeconds = 60;
    /** Readings taken for the baseline and again at the end; the verdict takes the median. */
    public int readings = 3;
    public int readingGapSeconds = 5;
    /**
     * How long a load has to write its first sample before it counts as one that will not start.
     * Builds run before the clock starts.
     */
    public int startupSeconds = 60;

    public Allowances allowances = new Allowances();

    /** Relative to the workspace unless absolute. */
    public String reportDir = "reports";
    /** Pid files, sample files and raw readings; relative to the workspace unless absolute. */
    public String stateDir = ".chaos";
    /** Leave the loads this run started running when it ends. */
    public boolean keep;

    public List<String> clients = new ArrayList<>(LANGUAGES);
    /** Per-client launch settings, laid over the defaults field by field. */
    public Map<String, Launch> launch = new LinkedHashMap<>();
    public List<KnownUpstream> knownUpstream = KnownUpstream.defaults();

    /** Growth a client is allowed before it is a leak. */
    public static final class Allowances {
        public int descriptors = 16;
        public int threads = 16;
        public double memoryFactor = 2;
    }

    /**
     * How one standing load is started. Strings may name {@code ${workspace}}, {@code ${state}}
     * and {@code ${broker}}.
     */
    public static final class Launch {
        /** Run to completion before the load starts; empty for none. */
        public List<String> build;
        public List<String> command;
        public Map<String, String> env;
        /** Working directory for both; the workspace when not given. */
        public String dir;
    }

    /**
     * A breach a library is known to have for a reason nothing in the workspace can fix, with a
     * ceiling of its own: {@code base * factor + perRecovery * recoveries + plus}. Growth within
     * it is reported under "Known upstream" and passes; past it is a failure like any other.
     */
    public static final class KnownUpstream {
        public String client;
        /** {@code threads}, {@code memory} or {@code descriptors}. */
        public String kind;
        public String why;
        public double factor = 1;
        public double perRecovery;
        public long plus;

        long ceiling(long base, int recoveries) {
            return (long) (base * factor) + (long) (perRecovery * recoveries) + plus;
        }

        static List<KnownUpstream> defaults() {
            List<KnownUpstream> out = new ArrayList<>();
            KnownUpstream threads = new KnownUpstream();
            threads.client = "ruby";
            threads.kind = "threads";
            threads.why = "bunny leaks a consumer work pool on every recovery: "
                    + "Channel#maybe_reinitialize_consumer_pool! (channel.rb:2038 in bunny "
                    + "3.4.0) replaces @work_pool and starts it without killing the old one";
            // Measured: 10 -> 168 threads over 240 recoveries on bunny 2.24 with a pool of 4,
            // which is 0.66 a recovery. 1.05 leaves room for the variance between runs and
            // still fails a second leak of the same size on top of this one.
            threads.perRecovery = 1.05;
            threads.plus = 16;
            out.add(threads);
            KnownUpstream memory = new KnownUpstream();
            memory.client = "ruby";
            memory.kind = "memory";
            memory.why = "the same pool leak retains whatever its threads were holding";
            // Measured: 43MB -> 111MB over 240 recoveries, 2.6x. 3x passes that and still
            // fails the 342MB a mismatched bunny produced.
            memory.factor = 3;
            out.add(memory);
            return out;
        }
    }

    /**
     * @param file an endurance YAML file, or null for the defaults
     * @return the configuration
     */
    public static EnduranceConfig read(Path file) {
        EnduranceConfig config = new EnduranceConfig();
        if (file == null) {
            return config;
        }
        try {
            new ObjectMapper(new YAMLFactory()).readerForUpdating(config)
                    .readValue(Files.readString(file));
        } catch (IOException e) {
            throw new ConfigException(file + ": " + e.getMessage().lines().findFirst().orElse(""));
        }
        return config;
    }

    /** @throws ConfigException if a number is out of range or a client has no way to start */
    public void validate() {
        if (cycles < 1 || cycleSeconds < 0 || warmupSeconds < 0 || sampleSeconds < 0
                || cooldownSeconds < 0 || readings < 1 || readingGapSeconds < 0 || startupSeconds < 0) {
            throw new ConfigException("cycles and readings must be at least 1, and every interval"
                    + " a whole number of seconds, zero or more");
        }
        if (clients.isEmpty()) {
            throw new ConfigException("no clients to watch");
        }
        for (String c : clients) {
            Launch l = launchFor(c);
            if (l.command == null || l.command.isEmpty()) {
                throw new ConfigException("no launch command for client '" + c + "'. Known by"
                        + " default: " + String.join(", ", LANGUAGES) + "; anything else needs"
                        + " launch." + c + ".command");
            }
        }
        for (KnownUpstream k : knownUpstream) {
            if (!List.of("threads", "memory", "descriptors").contains(k.kind)) {
                throw new ConfigException("knownUpstream kind must be threads, memory or"
                        + " descriptors, not '" + k.kind + "'");
            }
        }
    }

    Path workspacePath() {
        return Path.of(workspace).toAbsolutePath().normalize();
    }

    Path reportPath() {
        return workspacePath().resolve(reportDir).normalize();
    }

    Path statePath() {
        return workspacePath().resolve(stateDir).normalize();
    }

    String user() {
        return managementUser != null ? managementUser : userInfo(0);
    }

    String password() {
        return managementPassword != null ? managementPassword : userInfo(1);
    }

    private String userInfo(int part) {
        String info = URI.create(broker).getRawUserInfo();
        if (info == null) {
            return "guest";
        }
        String[] up = info.split(":", 2);
        return part < up.length ? URLDecoder.decode(up[part], StandardCharsets.UTF_8) : "guest";
    }

    /** @return the launch for a client: the default, with whatever the file set laid over it */
    public Launch launchFor(String client) {
        Launch base = defaultLaunch(client);
        Launch over = launch.get(client);
        if (over == null) {
            return base;
        }
        Launch out = new Launch();
        out.build = over.build != null ? over.build : base.build;
        out.command = over.command != null ? over.command : base.command;
        out.dir = over.dir != null ? over.dir : base.dir;
        out.env = new LinkedHashMap<>(base.env == null ? Map.of() : base.env);
        if (over.env != null) {
            out.env.putAll(over.env);
        }
        return out;
    }

    /**
     * How {@code scripts/chaos-drill.sh workload up} starts each language: the same programs, the
     * same environment, and always the process doing the publishing rather than a launcher that
     * execs it -- a pid that belongs to {@code go run}, {@code bundle exec} or {@code dotnet run}
     * is a pid whose resources are not the client's.
     */
    static Launch defaultLaunch(String client) {
        Launch l = new Launch();
        l.env = new LinkedHashMap<>();
        // Every standing load stops after a minute unless told otherwise; a soak wants it
        // running until it is stopped.
        l.env.put("ACEMQ_EXAMPLE_SECONDS", "0");
        switch (client) {
            case "java" -> {
                l.command = List.of("java", "-jar",
                        "${workspace}/acemq-java-amqp-workloads/library/target/acemq-workload.jar",
                        "-f", "${workspace}/config/chaos/workload/standing-load.yaml",
                        "--emit-samples", "--quiet");
                l.env.put("DRILL_BROKER", "${broker}");
            }
            case "go" -> {
                l.dir = "${workspace}/acemq-go-amqp-examples/advanced/"
                        + "08-a-standing-load-something-else-can-watch";
                l.build = List.of("go", "build", "-o", "${state}/standing-load-go", ".");
                l.command = List.of("${state}/standing-load-go", "-broker", "${broker}");
            }
            case "python" -> {
                l.command = List.of("${workspace}/acemq-python-amqp-examples/.venv/bin/python",
                        "${workspace}/acemq-python-amqp-examples/advanced/"
                                + "06-a-standing-load-something-else-can-watch/main.py");
                // A trailing slash on the default vhost; the Python load wants it.
                l.env.put("ACEMQ_URL", "${broker}/");
            }
            case "ruby" -> {
                // Not `bundle exec`: it execs a child, and the pid has to be the publisher.
                l.command = List.of("ruby", "-rbundler/setup",
                        "${workspace}/acemq-ruby-amqp-examples/advanced/"
                                + "05-a-standing-load-something-else-can-watch/main.rb");
                l.env.put("BUNDLE_GEMFILE", "${workspace}/acemq-ruby-amqp-examples/Gemfile");
                l.env.put("ACEMQ_URL", "${broker}");
            }
            case "dotnet" -> {
                // Built, then the apphost run directly: `dotnet run` would be the pid recorded.
                String project = "advanced/05-a-standing-load-something-else-can-watch-csharp";
                l.dir = "${workspace}/acemq-dotnet-amqp-examples";
                l.build = List.of("dotnet", "build", project, "-o", "${state}/standing-load-dotnet");
                l.command = List.of("${state}/standing-load-dotnet/"
                        + "05-a-standing-load-something-else-can-watch-csharp");
                l.env.put("ACEMQ_URL", "${broker}/");
            }
            default -> {
                // No default: the file has to say how.
            }
        }
        return l;
    }

    /** @return the string with the run's placeholders filled in */
    String expand(String s) {
        String brokerNoSlash = broker.endsWith("/") ? broker.substring(0, broker.length() - 1) : broker;
        return s.replace("${workspace}", workspacePath().toString())
                .replace("${state}", statePath().toString())
                .replace("${broker}", brokerNoSlash);
    }
}
