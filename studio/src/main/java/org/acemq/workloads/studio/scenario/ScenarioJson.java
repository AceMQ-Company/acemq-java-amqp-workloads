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
package org.acemq.workloads.studio.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.acemq.workloads.scenario.Binding;
import org.acemq.workloads.scenario.ConsumerGroupNode;
import org.acemq.workloads.scenario.ExchangeNode;
import org.acemq.workloads.scenario.ProducerNode;
import org.acemq.workloads.scenario.QueueNode;
import org.acemq.workloads.scenario.QueueType;
import org.acemq.workloads.scenario.Scenario;

/**
 * A scenario as a file.
 *
 * <p>This shape is the contract between the designer and everything else: it is what a saved
 * {@code acemq-workload-<name>-<date>.json} contains, what the studio sends over its API, and
 * what the command line will read. One shape, so a scenario drawn on a screen runs unchanged in a
 * pipeline — which is the only reason a designer is worth building rather than another form over
 * a YAML file.
 *
 * <p>Records rather than the builder objects because a file is data. The builder is convenient in
 * Java and hostile to a JSON parser, and a format that mirrors a fluent API is a format nobody can
 * write by hand.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioJson(
        String name,
        String description,
        String broker,
        String management,
        List<ExchangeJson> exchanges,
        List<QueueJson> queues,
        List<ProducerJson> producers,
        String warmup,
        String runFor,
        Boolean declare,
        Map<String, Object> ui) {

    /**
     * An exchange.
     *
     * @param name what it is called
     * @param type direct, topic, fanout or headers
     * @param durable whether it survives a restart
     * @param enabled whether it takes part
     * @param arguments anything else the broker takes
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeJson(String name, String type, Boolean durable, Boolean enabled,
            Map<String, Object> arguments) {
    }

    /**
     * A queue, its bindings and its consumers.
     *
     * @param name what it is called
     * @param type classic, classic-mirrored, quorum or stream
     * @param durable whether it survives a restart
     * @param enabled whether it takes part
     * @param deadLetterExchange where rejected messages go
     * @param bindings where its messages come from
     * @param consumers who reads it
     * @param arguments anything else the broker takes
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueJson(String name, String type, Boolean durable, Boolean enabled,
            String deadLetterExchange, List<BindingJson> bindings, ConsumersJson consumers,
            Map<String, Object> arguments) {
    }

    /**
     * @param exchange where the messages come from
     * @param routingKey the pattern they arrive under
     */
    public record BindingJson(String exchange, String routingKey) {
    }

    /**
     * @param concurrency how many consumers
     * @param prefetch how many unacknowledged messages each may hold
     * @param handlerTime how long the handler pretends to work
     * @param failureRate how often it throws, 0 to 1
     * @param enabled whether they run at all
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConsumersJson(Integer concurrency, Integer prefetch, String handlerTime,
            Double failureRate, Boolean enabled) {
    }

    /**
     * @param name what to call this source of load
     * @param exchange where it publishes
     * @param routingKeys the keys it publishes under, in turn
     * @param rate messages a second, 0 for as fast as it can
     * @param threads how many threads, or null to work it out from the rate
     * @param messageSize bytes per message
     * @param confirms whether it waits for the broker
     * @param maxInFlight how many publishes may be outstanding
     * @param maxMessages stop after this many
     * @param enabled whether it publishes
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProducerJson(String name, String exchange, List<String> routingKeys, Long rate,
            Integer threads, Integer messageSize, Boolean confirms, Integer maxInFlight,
            Long maxMessages, Boolean enabled) {
    }

    /** @return this file as a scenario the engine can run */
    public Scenario toScenario() {
        Scenario scenario = Scenario.named(name == null || name.isBlank() ? "scenario" : name)
                .describedAs(description)
                .warmup(parseDuration(warmup, Duration.ofSeconds(5)))
                .runFor(parseDuration(runFor, Duration.ofSeconds(30)));
        if (Boolean.FALSE.equals(declare)) {
            scenario.useExisting();
        }

        for (ExchangeJson exchange : nullToEmpty(exchanges)) {
            scenario.exchange(exchange.name(), exchange.type());
            ExchangeNode node = scenario.exchanges().get(scenario.exchanges().size() - 1);
            if (Boolean.FALSE.equals(exchange.durable())) {
                node.transient_();
            }
            if (Boolean.FALSE.equals(exchange.enabled())) {
                node.enabled(false);
            }
            nullToEmptyMap(exchange.arguments()).forEach(node::argument);
        }

        for (QueueJson queue : nullToEmpty(queues)) {
            scenario.queue(queue.name(), node -> {
                node.type(QueueType.parse(queue.type()));
                if (Boolean.FALSE.equals(queue.durable())) {
                    node.transient_();
                }
                if (Boolean.FALSE.equals(queue.enabled())) {
                    node.enabled(false);
                }
                if (queue.deadLetterExchange() != null && !queue.deadLetterExchange().isBlank()) {
                    node.deadLetterTo(queue.deadLetterExchange());
                }
                for (BindingJson binding : nullToEmpty(queue.bindings())) {
                    node.boundTo(binding.exchange(), binding.routingKey());
                }
                nullToEmptyMap(queue.arguments()).forEach(node::argument);

                ConsumersJson consumers = queue.consumers();
                if (consumers != null) {
                    node.consumers(group -> {
                        if (consumers.concurrency() != null) {
                            group.concurrency(consumers.concurrency());
                        }
                        if (consumers.prefetch() != null) {
                            group.prefetch(consumers.prefetch());
                        }
                        if (consumers.handlerTime() != null) {
                            group.handlerTime(parseDuration(consumers.handlerTime(), Duration.ZERO));
                        }
                        if (consumers.failureRate() != null) {
                            group.failureRate(consumers.failureRate());
                        }
                        if (consumers.enabled() != null) {
                            group.enabled(consumers.enabled());
                        }
                    });
                }
            });
        }

        for (ProducerJson producer : nullToEmpty(producers)) {
            scenario.producer(producer.name(), node -> {
                List<String> keys = nullToEmpty(producer.routingKeys());
                node.to(producer.exchange() == null ? "" : producer.exchange(),
                        keys.isEmpty() ? "" : keys.get(0));
                if (keys.size() > 1) {
                    node.routingKeys(keys.toArray(new String[0]));
                }
                if (producer.rate() != null) {
                    node.rate(producer.rate());
                }
                if (producer.threads() != null) {
                    node.threads(producer.threads());
                }
                if (producer.messageSize() != null) {
                    node.messageSize(producer.messageSize());
                }
                if (producer.confirms() != null) {
                    node.confirms(producer.confirms());
                }
                if (producer.maxInFlight() != null) {
                    node.maxInFlight(producer.maxInFlight());
                }
                if (producer.maxMessages() != null) {
                    node.maxMessages(producer.maxMessages());
                }
                if (producer.enabled() != null) {
                    node.enabled(producer.enabled());
                }
            });
        }

        return scenario;
    }

    /**
     * @param scenario a scenario
     * @param broker the URL it was designed against, or null
     * @param management its management URL, or null
     * @param ui whatever the designer wants to remember about layout
     * @return the same scenario as a file
     */
    public static ScenarioJson of(Scenario scenario, String broker, String management,
            Map<String, Object> ui) {
        List<ExchangeJson> exchanges = new ArrayList<>();
        for (ExchangeNode exchange : scenario.exchanges()) {
            exchanges.add(new ExchangeJson(exchange.name(), exchange.type(),
                    exchange.isDurable() ? null : Boolean.FALSE,
                    exchange.isEnabled() ? null : Boolean.FALSE,
                    exchange.arguments().isEmpty() ? null : exchange.arguments()));
        }

        List<QueueJson> queues = new ArrayList<>();
        for (QueueNode queue : scenario.queues()) {
            List<BindingJson> bindings = new ArrayList<>();
            for (Binding binding : queue.bindings()) {
                bindings.add(new BindingJson(binding.exchange(), binding.routingKey()));
            }
            ConsumerGroupNode consumers = queue.consumersNode();
            queues.add(new QueueJson(queue.name(),
                    queue.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    queue.isDurable() ? null : Boolean.FALSE,
                    queue.isEnabled() ? null : Boolean.FALSE,
                    queue.deadLetterExchange(),
                    bindings.isEmpty() ? null : bindings,
                    new ConsumersJson(consumers.concurrency(), consumers.prefetch(),
                            format(consumers.handlerTime()),
                            consumers.failureRate() == 0 ? null : consumers.failureRate(),
                            consumers.isEnabled() ? null : Boolean.FALSE),
                    null));
        }

        List<ProducerJson> producers = new ArrayList<>();
        for (ProducerNode producer : scenario.producers()) {
            producers.add(new ProducerJson(producer.name(), producer.exchange(),
                    producer.routingKeys(), producer.rate(), null, producer.messageSize(),
                    producer.confirms() ? null : Boolean.FALSE,
                    producer.maxInFlight(),
                    producer.maxMessages() == Long.MAX_VALUE ? null : producer.maxMessages(),
                    producer.isEnabled() ? null : Boolean.FALSE));
        }

        return new ScenarioJson(scenario.name(), scenario.description(), broker, management,
                exchanges, queues, producers, format(scenario.warmup()),
                format(scenario.duration()), scenario.shouldDeclare() ? null : Boolean.FALSE, ui);
    }

    /**
     * The file name a saved scenario gets.
     *
     * <p>Named for what it is and when it was saved, because the alternative is six files called
     * {@code scenario (3).json}.
     *
     * @param name the scenario's name
     * @param at when it was saved
     * @return a file name
     */
    public static String fileName(String name, java.time.LocalDate at) {
        String safe = (name == null || name.isBlank() ? "scenario" : name)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return "acemq-workload-" + (safe.isEmpty() ? "scenario" : safe) + "-" + at + ".json";
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, Object> nullToEmptyMap(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : map;
    }

    /**
     * Reads a duration the way the workload file does: {@code 10s}, {@code 2m}, {@code 500ms}.
     *
     * <p>ISO-8601 is also accepted, because something will eventually write {@code PT2M} and
     * refusing it would be pedantry.
     */
    static Duration parseDuration(String text, Duration fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String trimmed = text.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (trimmed.startsWith("p")) {
                return Duration.parse(trimmed);
            }
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2)));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not a duration; write it as 500ms, 30s, 2m or 1h");
        }
    }

    static String format(Duration duration) {
        if (duration == null || duration.isZero()) {
            return null;
        }
        if (duration.toMillis() % 60_000 == 0) {
            return duration.toMinutes() + "m";
        }
        if (duration.toMillis() % 1_000 == 0) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }
}
