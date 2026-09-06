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
import org.acemq.workloads.Workload;
import org.acemq.workloads.rules.Objective;

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

    private WorkloadFile(List<Workload> workloads, List<String> brokerUrls) {
        this.workloads = workloads;
        this.brokerUrls = brokerUrls;
    }

    /**
     * @param path a {@code .yaml}, {@code .yml} or {@code .json} file
     * @return the workloads in it
     */
    public static WorkloadFile read(Path path) {
        return read(path, System::getenv);
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
        String resolved = substitute(text, environment);
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

        if (root.has("workloads")) {
            JsonNode list = root.get("workloads");
            if (!list.isArray() || list.isEmpty()) {
                throw new ConfigException("'workloads' must be a non-empty list");
            }
            // A suite shares whatever the top level declares, so a comparison between two
            // configurations does not repeat the broker URL and invite them to drift apart.
            for (JsonNode entry : list) {
                JsonNode merged = merge(root, entry);
                workloads.add(toWorkload(merged));
                brokers.add(requiredText(merged, "broker"));
            }
        } else {
            workloads.add(toWorkload(root));
            brokers.add(requiredText(root, "broker"));
        }
        return new WorkloadFile(workloads, brokers);
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
        reject(node, "", "name", "broker", "management", "managementUser", "managementPassword",
                "topology", "publishers", "consumers", "warmup", "runFor", "expect");

        Workload.Builder builder = Workload.named(requiredText(node, "name"));

        JsonNode topology = node.path("topology");
        if (!topology.isMissingNode()) {
            reject(topology, "topology.", "exchange", "exchangeType", "queue", "routingKey",
                    "queueType", "arguments", "declare");
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
                if ("quorum".equals(topology.path("queueType").asText("classic"))) {
                    t.quorum();
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
                    "randomPayload", "confirms", "maxInFlight", "maxMessages");
            builder.publishers(p -> {
                if (publishers.has("threads")) {
                    p.threads(publishers.get("threads").asInt());
                }
                if (publishers.path("unthrottled").asBoolean(false)) {
                    p.unthrottled();
                } else if (publishers.has("rate")) {
                    p.rate(publishers.get("rate").asLong());
                }
                if (publishers.has("messageSize")) {
                    int size = publishers.get("messageSize").asInt();
                    p.payload(publishers.path("randomPayload").asBoolean(false)
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
            });
        }

        JsonNode consumers = node.path("consumers");
        if (!consumers.isMissingNode()) {
            reject(consumers, "consumers.", "concurrency", "prefetch", "handlerTime", "failureRate");
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
            });
        }

        if (node.has("warmup")) {
            builder.warmup(Durations.parse(node.get("warmup").asText(), "warmup"));
        }
        if (node.has("runFor")) {
            builder.runFor(Durations.parse(node.get("runFor").asText(), "runFor"));
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
        return builder.build();
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

    /** Replaces {@code ${VAR}} and {@code ${VAR:-default}} from the environment. */
    static String substitute(String text, Function<String, String> environment) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
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
     * @param index which workload
     * @return the broker URL it runs against
     */
    public String brokerUrl(int index) {
        return brokerUrls.get(index);
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
            out.append("  broker      ").append(redact(brokerUrls.get(i))).append('\n');
            out.append("  ").append(workload.topology()).append('\n');
            out.append("  ").append(workload.publishers()).append('\n');
            out.append("  ").append(workload.consumers()).append('\n');
            out.append("  warmup      ").append(Durations.format(workload.warmup())).append('\n');
            out.append("  runFor      ").append(Durations.format(workload.duration())).append('\n');
            out.append("  rules       ").append(workload.rules().size()).append('\n');
        }
        return out.toString();
    }

    /** Hides the password in an AMQP URL, so a dry run is safe to paste into a ticket. */
    static String redact(String url) {
        return url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }
}
