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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.acemq.workloads.Payload;
import org.acemq.workloads.Ramp;
import org.acemq.workloads.Workload;
import org.acemq.workloads.rules.Objective;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Severity;
import org.acemq.workloads.rules.Threshold;
import org.acemq.workloads.scenario.YamlComments;

/**
 * Reads a workload, or a suite of them, from YAML or JSON.
 *
 * <pre>{@code
 * name: orders-peak
 * broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
 *
 * topology:
 *   exchange: orders
 *   queue: orders.new
 *   routingKey: order.created
 *
 * publishers:
 *   threads: 4
 *   rate: 50000
 *   messageSize: 1024
 *
 * consumers:
 *   concurrency: 8
 *   prefetch: 100
 *   handlerTime: 1ms
 *
 * warmup: 10s
 * runFor: 2m
 *
 * expect:
 *   throughputAtLeast: 45000
 *   p99Below: 50ms
 * }</pre>
 *
 * <h2>Secrets do not belong in this file</h2>
 *
 * <p>A workload file is meant to be committed and reviewed — that is most of its value. So
 * {@code ${VAR}} is substituted from the environment before parsing, and a password written
 * literally is a password in your git history.
 *
 * <h2>The broker can come from outside the file</h2>
 *
 * <p>{@code broker} is optional here, because {@code --broker} can supply it. A file that names no
 * broker is the right shape for one that is committed and pointed at a different cluster each time
 * it runs, and refusing it would make the flag able only to override. Missing from both is still an
 * error: see {@link #withBroker}.
 *
 * <h2>Unknown keys are refused</h2>
 *
 * <p>A misspelled {@code prefech: 100} that is silently ignored produces a run with the default
 * prefetch and a report that says nothing about it. The result looks completely normal and
 * answers a different question from the one asked.
 */
public final class WorkloadFile {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    private final List<Workload> workloads;
    private final List<String> brokerUrls;
    // One per workload, null where the workload is a single run rather than a ramp.
    private final List<Ramp> ramps;
    // The ramp blocks as written, so --ramp can be laid over them key by key.
    private final List<JsonNode> rampNodes;

    private WorkloadFile(List<Workload> workloads, List<String> brokerUrls, List<Ramp> ramps,
            List<JsonNode> rampNodes) {
        this.workloads = workloads;
        this.brokerUrls = brokerUrls;
        this.ramps = ramps;
        this.rampNodes = rampNodes;
    }

    /**
     * @param path a {@code .yaml}, {@code .yml} or {@code .json} file
     * @return the workloads in it
     */
    public static WorkloadFile read(Path path) {
        return read(path, System::getenv);
    }

    /**
     * Reads workload YAML that is already in memory, exactly as {@link #read(Path)} reads a file.
     *
     * <p>For a tool that edits the file before it exists on disk — the studio's load designer
     * validates what it is about to write with this, so "valid" there means what it means to the
     * command line, rather than to a second parser that agrees most of the time.
     *
     * @param yaml the workload file's text
     * @param environment how {@code ${VAR}} references are resolved
     * @return the workloads in it
     * @throws ConfigException with a reason somebody can act on, when the file is not valid
     */
    public static WorkloadFile parseYaml(String yaml, Function<String, String> environment) {
        return parse(yaml, false, environment);
    }

    static WorkloadFile read(Path path, Function<String, String> environment) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigException("could not read " + path + ": " + e.getMessage());
        }
        return parse(text, isJson(path), environment);
    }

    static WorkloadFile parse(String text, boolean json, Function<String, String> environment) {
        String resolved = substitute(text, environment, !json);
        ObjectMapper mapper = json ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());

        JsonNode root;
        try {
            root = mapper.readTree(resolved);
        } catch (IOException e) {
            throw new ConfigException("this file is not valid " + (json ? "JSON" : "YAML")
                    + ": " + e.getMessage());
        }
        if (root == null || root.isNull()) {
            throw new ConfigException("the workload file is empty");
        }

        List<Workload> workloads = new ArrayList<>();
        List<String> brokers = new ArrayList<>();
        List<Ramp> ramps = new ArrayList<>();
        List<JsonNode> rampNodes = new ArrayList<>();

        if (root.has("workloads")) {
            JsonNode list = root.get("workloads");
            if (!list.isArray() || list.isEmpty()) {
                throw new ConfigException("'workloads' must be a non-empty list");
            }
            // A suite shares whatever the top level declares, so a comparison between two
            // configurations does not repeat the broker URL and invite them to drift apart.
            for (JsonNode entry : list) {
                JsonNode merged = merge(root, entry);
                Workload workload = toWorkload(merged);
                workloads.add(workload);
                brokers.add(optionalText(merged, "broker"));
                ramps.add(merged.has("ramp") ? toRamp(workload, merged.get("ramp")) : null);
                rampNodes.add(merged.get("ramp"));
            }
        } else {
            Workload workload = toWorkload(root);
            workloads.add(workload);
            brokers.add(optionalText(root, "broker"));
            ramps.add(root.has("ramp") ? toRamp(workload, root.get("ramp")) : null);
            rampNodes.add(root.get("ramp"));
        }
        return new WorkloadFile(workloads, brokers, ramps, rampNodes);
    }

    /** Copies the top-level keys a suite entry does not override. */
    private static JsonNode merge(JsonNode parent, JsonNode child) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        parent.fields().forEachRemaining(e -> {
            if (!"workloads".equals(e.getKey())) {
                fields.put(e.getKey(), e.getValue());
            }
        });
        child.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));

        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        fields.forEach(node::set);
        return node;
    }

    private static Workload toWorkload(JsonNode node) {
        // A value the builders refuse -- zero threads, an outbox with no rate -- is a bad file,
        // exit 3, and not a stack trace.
        try {
            return buildWorkload(node);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("'" + requiredText(node, "name") + "': " + e.getMessage());
        }
    }

    private static Workload buildWorkload(JsonNode node) {
        reject(node, "", "name", "broker", "management", "managementUser", "managementPassword",
                "topology", "publishers", "consumers", "warmup", "runFor", "expect", "rules",
                "ramp");

        Workload.Builder builder = Workload.named(requiredText(node, "name"));

        JsonNode topology = node.path("topology");
        if (!topology.isMissingNode()) {
            reject(topology, "topology.", "exchange", "exchangeType", "queue", "routingKey",
                    "queueType", "arguments", "declare");
            // An exchange type with no exchange to apply it to. Without an exchange the workload
            // publishes through the default one, which has no type and cannot be declared, so
            // there is nowhere for this value to go — and a file that says fanout and measures
            // a direct-to-queue publish is exactly the kind of wrong that reads as right.
            // A workload is one queue, and the two kinds it can be are the two a single path is
            // worth comparing. "stream" parses elsewhere in this project and does not belong
            // here: a stream is read by offset and deletes nothing, so the consumers, the depth
            // and the backlog rules all mean something different — it is a scenario's queue, and
            // 06-stream.yaml is what asking for one looks like. Refused rather than quietly
            // downgraded to classic, which is what this used to do.
            String queueType = topology.path("queueType").asText("classic");
            if (!"classic".equals(queueType) && !"quorum".equals(queueType)) {
                throw new ConfigException("'topology.queueType' is '" + queueType + "', and a"
                        + " workload file understands 'classic' or 'quorum'. For a stream, or for"
                        + " several queues of different kinds at once, write a scenario file:"
                        + " its queues take a 'type' and this one does not.");
            }

            if (topology.has("exchangeType") && !topology.has("exchange")) {
                throw new ConfigException("'topology.exchangeType' was given without"
                        + " 'topology.exchange'. With no exchange the workload publishes to the"
                        + " default exchange, which routes by queue name and has no type, so the"
                        + " type would be ignored.");
            }
            builder.topology(t -> {
                if (topology.has("exchange")) {
                    t.exchange(topology.get("exchange").asText(),
                            topology.path("exchangeType").asText("topic"));
                }
                if (topology.has("queue")) {
                    t.queue(topology.get("queue").asText());
                }
                if (topology.has("routingKey")) {
                    t.routingKey(topology.get("routingKey").asText());
                }
                if ("quorum".equals(queueType)) {
                    t.quorum();
                } else {
                    t.classic();
                }
                topology.path("arguments").fields().forEachRemaining(e ->
                        t.argument(e.getKey(), e.getValue().isNumber()
                                ? e.getValue().numberValue() : e.getValue().asText()));
                if (topology.has("declare") && !topology.get("declare").asBoolean()) {
                    t.useExisting();
                }
            });
        }

        JsonNode publishers = node.path("publishers");
        if (!publishers.isMissingNode()) {
            reject(publishers, "publishers.", "threads", "rate", "unthrottled", "messageSize",
                    "randomPayload", "confirms", "maxInFlight", "maxMessages", "claimCheck",
                    "outbox");
            JsonNode claimCheck = publishers.path("claimCheck");
            if (!claimCheck.isMissingNode()) {
                if (!claimCheck.isObject()) {
                    throw new ConfigException("'publishers.claimCheck' must be a mapping:"
                            + " threshold (bytes), directory (or memory when absent)");
                }
                reject(claimCheck, "publishers.claimCheck.", "threshold", "directory");
            }
            JsonNode outbox = publishers.path("outbox");
            if (!outbox.isMissingNode()) {
                if (!outbox.isObject()) {
                    throw new ConfigException("'publishers.outbox' must be a mapping:"
                            + " batchSize, pollInterval");
                }
                reject(outbox, "publishers.outbox.", "batchSize", "pollInterval");
            }
            builder.publishers(p -> {
                if (publishers.has("threads")) {
                    p.threads(publishers.get("threads").asInt());
                }
                if (publishers.path("unthrottled").asBoolean(false)) {
                    p.unthrottled();
                } else if (publishers.has("rate")) {
                    p.rate(publishers.get("rate").asLong());
                }
                // The two payload keys are one setting between them, and either can be written
                // without the other. Reading randomPayload only inside messageSize meant a file
                // that asked for an incompressible body and left the size alone got a kilobyte
                // of zeroes instead — which is the payload a broker's own compression is best at
                // and therefore the one that flatters it most.
                if (publishers.has("messageSize") || publishers.has("randomPayload")) {
                    int size = publishers.path("messageSize").asInt(p.payload().size());
                    p.payload(publishers.path("randomPayload").asBoolean(p.payload().isRandom())
                            ? Payload.ofRandomBytes(size) : Payload.ofBytes(size));
                }
                if (publishers.has("confirms")) {
                    p.confirms(publishers.get("confirms").asBoolean());
                }
                if (publishers.has("maxInFlight")) {
                    p.maxInFlight(publishers.get("maxInFlight").asInt());
                }
                if (publishers.has("maxMessages")) {
                    p.maxMessages(publishers.get("maxMessages").asLong());
                }
                if (!claimCheck.isMissingNode()) {
                    String directory = optionalText(claimCheck, "directory");
                    p.claimCheck(claimCheck.has("threshold")
                                    ? (int) whole(claimCheck.get("threshold"), "publishers.claimCheck.threshold")
                                    : org.acemq.amqp.patterns.ClaimCheckCodec.DEFAULT_THRESHOLD,
                            directory == null ? null : Path.of(directory));
                }
                if (!outbox.isMissingNode()) {
                    int batchSize = outbox.has("batchSize")
                            ? (int) whole(outbox.get("batchSize"), "publishers.outbox.batchSize") : 100;
                    if (batchSize < 1) {
                        throw new ConfigException("'publishers.outbox.batchSize' must be at least 1");
                    }
                    p.outbox(batchSize, outbox.has("pollInterval")
                            ? Durations.parse(outbox.get("pollInterval").asText(), "publishers.outbox.pollInterval")
                            : Duration.ofMillis(100));
                }
            });
        }

        JsonNode consumers = node.path("consumers");
        if (!consumers.isMissingNode()) {
            reject(consumers, "consumers.", "concurrency", "prefetch", "handlerTime", "failureRate",
                    "retry");
            JsonNode retry = consumers.path("retry");
            org.acemq.amqp.api.RetryPolicy policy = retry.isMissingNode() ? null : toRetry(retry);
            builder.consumers(c -> {
                if (consumers.has("concurrency")) {
                    c.concurrency(consumers.get("concurrency").asInt());
                }
                if (consumers.has("prefetch")) {
                    c.prefetch(consumers.get("prefetch").asInt());
                }
                if (consumers.has("handlerTime")) {
                    c.handlerTime(Durations.parse(consumers.get("handlerTime").asText(),
                            "consumers.handlerTime"));
                }
                if (consumers.has("failureRate")) {
                    c.failureRate(consumers.get("failureRate").asDouble());
                }
                if (policy != null) {
                    c.retry(policy);
                }
            });
        }

        if (node.has("warmup")) {
            builder.warmup(Durations.parse(node.get("warmup").asText(), "warmup"));
        }
        if (node.has("runFor")) {
            String runFor = node.get("runFor").asText().trim();
            // Two spellings on purpose. "until-stopped" says what it does and "forever" is what
            // somebody writes when they mean it; refusing either in favour of the other buys a
            // configuration error and nothing else.
            if (runFor.equalsIgnoreCase("until-stopped") || runFor.equalsIgnoreCase("forever")) {
                builder.runUntilStopped();
            } else {
                builder.runFor(Durations.parse(runFor, "runFor"));
            }
        }
        if (node.has("management")) {
            builder.management(node.get("management").asText(),
                    node.path("managementUser").asText("guest"),
                    node.path("managementPassword").asText("guest"));
        }

        JsonNode expect = node.path("expect");
        if (!expect.isMissingNode()) {
            reject(expect, "expect.", "throughputAtLeast", "p99Below", "p999Below",
                    "p50Below", "noMessagesLost");
            if (expect.has("throughputAtLeast")) {
                builder.expect(Objective.throughputAtLeast(expect.get("throughputAtLeast").asLong()));
            }
            if (expect.has("p50Below")) {
                builder.expect(Objective.percentileBelow(50.0,
                        Durations.parse(expect.get("p50Below").asText(), "expect.p50Below")));
            }
            if (expect.has("p99Below")) {
                builder.expect(Objective.p99Below(
                        Durations.parse(expect.get("p99Below").asText(), "expect.p99Below")));
            }
            if (expect.has("p999Below")) {
                builder.expect(Objective.percentileBelow(99.9,
                        Durations.parse(expect.get("p999Below").asText(), "expect.p999Below")));
            }
            if (expect.path("noMessagesLost").asBoolean(false)) {
                builder.expect(Objective.noMessagesLost());
            }
        }

        JsonNode rules = node.path("rules");
        if (!rules.isMissingNode()) {
            if (!rules.isArray()) {
                throw new ConfigException("'rules' must be a list of rules, each with a name, a"
                        + " metric and 'above' or 'below'");
            }
            for (int i = 0; i < rules.size(); i++) {
                builder.rule(toRule(rules.get(i), "rules[" + i + "]"));
            }
        }
        return builder.build();
    }

    /**
     * A retry ladder from the file: the library's {@code RetryPolicy}, fixed or exponential.
     *
     * <pre>{@code
     * retry:
     *   attempts: 4          # deliveries in total, the first included
     *   delay: 200ms         # fixed; or initialDelay + maxDelay (+ multiplier) for exponential
     *   brokerFrom: 1s       # waits this long or longer are spent in a rung queue (default 30s)
     *   jitter: 0.2          # share of a consumer-held wait to spread by
     * }</pre>
     */
    static org.acemq.amqp.api.RetryPolicy toRetry(JsonNode node) {
        if (!node.isObject()) {
            throw new ConfigException("'consumers.retry' must be a mapping: attempts, and delay or"
                    + " initialDelay with maxDelay");
        }
        reject(node, "consumers.retry.", "attempts", "delay", "initialDelay", "maxDelay",
                "multiplier", "brokerFrom", "jitter");
        if (!node.has("attempts")) {
            throw new ConfigException("'consumers.retry.attempts' is required: how many deliveries"
                    + " a message gets in total, the first included");
        }
        int attempts = (int) whole(node.get("attempts"), "consumers.retry.attempts");
        if (attempts < 2) {
            throw new ConfigException("'consumers.retry.attempts' is " + attempts + "; a ladder"
                    + " needs at least 2, and a failure with no retry is what no 'retry' block does");
        }
        boolean fixed = node.has("delay");
        if (fixed == (node.has("initialDelay") || node.has("maxDelay"))) {
            throw new ConfigException("'consumers.retry' needs either 'delay' (the same wait each"
                    + " time) or 'initialDelay' and 'maxDelay' (doubling), not both and not neither");
        }
        try {
            org.acemq.amqp.api.RetryPolicy policy;
            if (fixed) {
                policy = org.acemq.amqp.api.RetryPolicy.fixed(attempts,
                        Durations.parse(node.get("delay").asText(), "consumers.retry.delay"));
            } else {
                if (!node.has("initialDelay") || !node.has("maxDelay")) {
                    throw new ConfigException("'consumers.retry' needs both 'initialDelay' and"
                            + " 'maxDelay' for a doubling schedule");
                }
                policy = org.acemq.amqp.api.RetryPolicy.exponential(attempts,
                        Durations.parse(node.get("initialDelay").asText(), "consumers.retry.initialDelay"),
                        node.has("multiplier") ? number(node.get("multiplier"), "consumers.retry.multiplier") : 2.0,
                        Durations.parse(node.get("maxDelay").asText(), "consumers.retry.maxDelay"));
            }
            if (node.has("brokerFrom")) {
                policy = policy.waitInBrokerFrom(
                        Durations.parse(node.get("brokerFrom").asText(), "consumers.retry.brokerFrom"));
            }
            if (node.has("jitter")) {
                policy = policy.withJitter(number(node.get("jitter"), "consumers.retry.jitter"));
            }
            return policy;
        } catch (IllegalArgumentException e) {
            throw new ConfigException("'consumers.retry': " + e.getMessage());
        }
    }

    /**
     * One rule from the file: a threshold on one metric, the shape of the admin library's alerts.
     *
     * <pre>{@code
     * - name: backlog-stays-small
     *   metric: queueDepthAtEnd
     *   above: 1000
     *   severity: failed
     *   because: a queue that ends deep was filling
     * }</pre>
     */
    static Rule toRule(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new ConfigException("'" + where + "' must be a mapping with name, metric and"
                    + " 'above' or 'below'");
        }
        reject(node, where + ".", "name", "metric", "above", "below", "severity", "because");
        String name = optionalText(node, "name");
        if (name == null) {
            throw new ConfigException("'" + where + ".name' is required: it is what the report"
                    + " calls the finding");
        }
        String metric = optionalText(node, "metric");
        if (metric == null || !Threshold.metricNames().contains(metric)) {
            throw new ConfigException("'" + where + ".metric' is "
                    + (metric == null ? "missing" : "'" + metric + "', which is not a metric")
                    + ". Known metrics: " + String.join(", ", Threshold.metricNames()));
        }
        boolean hasAbove = node.has("above");
        if (hasAbove == node.has("below")) {
            throw new ConfigException("'" + where + "' needs exactly one of 'above' or 'below'");
        }
        String limitField = hasAbove ? "above" : "below";
        JsonNode limit = node.get(limitField);
        Threshold threshold = Threshold.named(name).on(metric);
        String field = where + "." + limitField;
        if (Threshold.isLatency(metric)) {
            Duration d = Durations.parse(limit.asText(), field);
            threshold = hasAbove ? threshold.above(d) : threshold.below(d);
        } else {
            double value = "errorRate".equals(metric) ? fraction(limit, field) : number(limit, field);
            threshold = hasAbove ? threshold.above(value) : threshold.below(value);
        }
        if (node.has("severity")) {
            String severity = node.get("severity").asText().trim().toUpperCase(java.util.Locale.ROOT);
            try {
                threshold.severity(Severity.valueOf(severity));
            } catch (IllegalArgumentException e) {
                throw new ConfigException("'" + where + ".severity' is '"
                        + node.get("severity").asText() + "'. Known: invalid, failed, warning,"
                        + " info");
            }
        }
        return threshold.because(optionalText(node, "because"));
    }

    /**
     * A ramp from the file, over the workload it sits in.
     *
     * <pre>{@code
     * ramp:
     *   start: 1000
     *   step: 1000        # or 25%
     *   stepFor: 20s
     *   warmup: 5s
     *   maxRate: 100000
     *   refine: 3
     * }</pre>
     */
    static Ramp toRamp(Workload workload, JsonNode node) {
        if (!node.isObject()) {
            throw new ConfigException("'ramp' must be a mapping: start, step, stepFor, warmup,"
                    + " maxRate, maxErrorRate, tolerance, refine, ceilingAtLeast");
        }
        reject(node, "ramp.", "start", "step", "stepFor", "warmup", "maxRate", "maxErrorRate",
                "tolerance", "refine", "ceilingAtLeast");
        try {
            Ramp ramp = Ramp.of(workload);
            if (node.has("start")) {
                ramp.startAt(whole(node.get("start"), "ramp.start"));
            }
            if (!node.has("step")) {
                throw new ConfigException("'ramp.step' is required: a rate such as 1000, or a"
                        + " percentage such as 25%");
            }
            String step = node.get("step").asText().trim();
            if (step.endsWith("%")) {
                ramp.stepByPercent(number(new com.fasterxml.jackson.databind.node.TextNode(
                        step.substring(0, step.length() - 1)), "ramp.step"));
            } else {
                ramp.stepBy(whole(node.get("step"), "ramp.step"));
            }
            if (node.has("stepFor")) {
                ramp.stepFor(Durations.parse(node.get("stepFor").asText(), "ramp.stepFor"));
            }
            if (node.has("warmup")) {
                ramp.warmupEachStep(Durations.parse(node.get("warmup").asText(), "ramp.warmup"));
            }
            if (node.has("maxRate")) {
                ramp.maxRate(whole(node.get("maxRate"), "ramp.maxRate"));
            }
            if (node.has("maxErrorRate")) {
                ramp.maxErrorRate(fraction(node.get("maxErrorRate"), "ramp.maxErrorRate"));
            }
            if (node.has("tolerance")) {
                ramp.tolerance(fraction(node.get("tolerance"), "ramp.tolerance"));
            }
            if (node.has("refine")) {
                ramp.refine((int) whole(node.get("refine"), "ramp.refine"));
            }
            if (node.has("ceilingAtLeast")) {
                ramp.ceilingAtLeast(whole(node.get("ceilingAtLeast"), "ramp.ceilingAtLeast"));
            }
            return ramp.check();
        } catch (IllegalArgumentException e) {
            throw new ConfigException("ramp in '" + workload.name() + "': " + e.getMessage());
        }
    }

    /**
     * Makes every workload in the file a ramp, from {@code --ramp start=1000,step=10%,...}.
     *
     * <p>The flag is the {@code ramp:} block written on one line, and goes through the same parser,
     * so the two cannot disagree about what a setting means. Where the file already has a ramp the
     * flag's settings win, key by key.
     *
     * @param spec comma-separated {@code key=value} pairs, or null to change nothing
     * @return the same file, with ramps
     */
    public WorkloadFile withRamp(String spec) {
        if (spec == null) {
            return this;
        }
        com.fasterxml.jackson.databind.node.ObjectNode flag = new ObjectMapper().createObjectNode();
        for (String pair : spec.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new ConfigException("--ramp takes key=value pairs separated by commas, such"
                        + " as start=1000,step=10%,stepFor=20s; '" + pair + "' is not one");
            }
            flag.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        List<Ramp> resolved = new ArrayList<>();
        for (int i = 0; i < workloads.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode merged = new ObjectMapper().createObjectNode();
            if (rampNodes.get(i) != null && rampNodes.get(i).isObject()) {
                merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) rampNodes.get(i));
            }
            merged.setAll(flag);
            resolved.add(toRamp(workloads.get(i), merged));
        }
        return new WorkloadFile(workloads, brokerUrls, resolved, rampNodes);
    }

    private static double number(JsonNode value, String field) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("'" + field + "' is '" + value.asText() + "', which is not"
                    + " a number");
        }
    }

    private static long whole(JsonNode value, String field) {
        double number = number(value, field);
        if (number != Math.rint(number) || number < 0) {
            throw new ConfigException("'" + field + "' is '" + value.asText() + "'; it must be a"
                    + " whole number, zero or more");
        }
        return (long) number;
    }

    /** A share written as {@code 5%} or as {@code 0.05}. */
    private static double fraction(JsonNode value, String field) {
        String text = value.asText().trim();
        double share = text.endsWith("%")
                ? number(new com.fasterxml.jackson.databind.node.TextNode(
                        text.substring(0, text.length() - 1)), field) / 100
                : number(value, field);
        if (share < 0 || share >= 1) {
            throw new ConfigException("'" + field + "' is '" + text + "'; write a share such as"
                    + " 5% or 0.05, from 0 up to (not including) 100%");
        }
        return share;
    }

    /** Fails on a key this version does not know, rather than ignoring it. */
    private static void reject(JsonNode node, String prefix, String... known) {
        List<String> allowed = List.of(known);
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new ConfigException("unknown setting '" + prefix + field + "'."
                        + " Known settings here: " + String.join(", ", allowed)
                        + ". A misspelled setting that was ignored would run with the default"
                        + " and report a result that looks entirely normal.");
            }
        });
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new ConfigException("'" + field + "' is required");
        }
        return value.asText();
    }

    /** @return the field, or null when the file does not say. */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /**
     * Replaces {@code ${VAR}} and {@code ${VAR:-default}} from the environment, outside comments.
     *
     * <p>Substitution runs over the whole file before anything parses it, which is what lets a
     * placeholder stand anywhere — a broker URL, a rate, a queue name — rather than only in the
     * few fields somebody anticipated. It used to run over the comments too, so a file that
     * tried to explain its own syntax to the next reader stopped the run with an unset-variable
     * error over a line the parser never sees. A comment is not part of the document.
     *
     * <p>A placeholder in a comment is left exactly as written even when the variable <em>is</em>
     * set, because resolving it would write the value into the comment, and the value is often
     * the password the placeholder exists to keep out of the file.
     *
     * @param text the file's contents
     * @param environment where {@code ${VAR}} comes from
     * @param yaml whether {@code #} starts a comment, which it does not in JSON
     */
    static String substitute(String text, Function<String, String> environment, boolean yaml) {
        boolean[] comment = yaml ? YamlComments.mask(text) : new boolean[text.length()];
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (comment[matcher.start()]) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String value = environment.apply(name);
            if (value == null) {
                if (fallback == null) {
                    throw new ConfigException("the workload file refers to ${" + name + "},"
                            + " which is not set. Export it, or write ${" + name + ":-default}."
                            + " Secrets belong in the environment rather than in a file that"
                            + " gets committed.");
                }
                value = fallback;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isJson(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    /** @return the workloads, in file order */
    public List<Workload> workloads() {
        return List.copyOf(workloads);
    }

    /**
     * Settles which broker each workload runs against, the command line winning over the file.
     *
     * <p>Where the broker comes from cannot be decided while the file is being parsed, because the
     * flag is not in the file. Deciding it there anyway is the bug this method exists to remove:
     * {@code broker} was required of the file, so a file written to be pointed at a broker — one
     * committed to a repository that has no business naming somebody's staging cluster — was
     * refused before the flag that supplies the broker was ever looked at. The flag documented as
     * overriding the file could therefore only override, never supply, and in fact did neither: the
     * command line read it and then ran the file's broker regardless.
     *
     * <p>A suite gets the one override for every workload in it. That is the same reading as
     * "overriding the file": the file's brokers are what is being replaced, and replacing some of
     * them would leave a run that is half against one broker and half against another with nothing
     * saying which half is which.
     *
     * @param override the {@code --broker} URL, or null when the command line did not say
     * @return the same workloads, each with the broker it will run against
     * @throws ConfigException if a workload is left without a broker from either source
     */
    public WorkloadFile withBroker(String override) {
        boolean given = override != null && !override.isBlank();
        List<String> resolved = new ArrayList<>(brokerUrls.size());
        for (int i = 0; i < brokerUrls.size(); i++) {
            String url = given ? override : brokerUrls.get(i);
            if (url == null || url.isBlank()) {
                throw new ConfigException("no broker to run '" + workloads.get(i).name()
                        + "' against: 'broker' is missing from the file and --broker was not"
                        + " given. Put one in the file as 'broker: amqp://...', or pass"
                        + " --broker amqp://guest:guest@localhost:5672.");
            }
            resolved.add(url);
        }
        return new WorkloadFile(workloads, resolved, ramps, rampNodes);
    }

    /**
     * @param index which workload
     * @return the broker URL it runs against
     * @throws ConfigException if neither the file nor {@link #withBroker} supplied one
     */
    public String brokerUrl(int index) {
        String url = brokerUrls.get(index);
        if (url == null || url.isBlank()) {
            throw new ConfigException("no broker to run '" + workloads.get(index).name()
                    + "' against: 'broker' is missing from the file and none was supplied.");
        }
        return url;
    }

    /**
     * @param index which workload
     * @return the ramp to run it as, or null when it is a single run
     */
    public Ramp ramp(int index) {
        return ramps.get(index);
    }

    /** @return how many workloads this file holds */
    public int size() {
        return workloads.size();
    }

    /** @return the resolved configuration, for {@code --dry-run} */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < workloads.size(); i++) {
            Workload workload = workloads.get(i);
            out.append(workload.name()).append('\n');
            out.append("  broker      ").append(redact(brokerUrl(i))).append('\n');
            out.append("  ").append(workload.topology()).append('\n');
            out.append("  ").append(workload.publishers()).append('\n');
            out.append("  ").append(workload.consumers()).append('\n');
            out.append("  warmup      ").append(Durations.format(workload.warmup())).append('\n');
            // Not the sentinel's own value. `--dry-run' is read to check what a run is about to do,
            // and "87600h" is a number somebody would try to explain rather than a run that ends
            // when it is stopped.
            out.append("  runFor      ")
                    .append(workload.runsUntilStopped()
                            ? "until stopped"
                            : Durations.format(workload.duration()))
                    .append('\n');
            out.append("  rules       ").append(workload.rules().size()).append('\n');
            if (ramps.get(i) != null) {
                out.append("  ramp        ").append(ramps.get(i)).append('\n');
            }
        }
        return out.toString();
    }

    /** Hides the password in an AMQP URL, so a dry run is safe to paste into a ticket. */
    static String redact(String url) {
        return url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }
}
