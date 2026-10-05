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
package org.acemq.workloads.studio.console;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import org.acemq.workloads.Workload;
import org.acemq.workloads.cli.ConfigException;
import org.acemq.workloads.cli.WorkloadFile;

/**
 * The load designer's two halves: the form written out as a workload file, and a workload file
 * checked by the library's own parser.
 *
 * <p>Only the keys {@link WorkloadFile} reads are ever written, and only when the form set them,
 * so the file is the one somebody would have written by hand and runs unchanged with
 * {@code java -jar acemq-workload.jar -f}.
 */
public final class WorkloadYaml {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private WorkloadYaml() {
    }

    /**
     * The designer's fields, one per key of the workload file. A null field is left out of the
     * file, which means the library's default applies.
     */
    public record Form(String name, String broker,
            String exchange, String exchangeType, String queue, String routingKey,
            String queueType, Boolean declare,
            Integer threads, Long rate, Boolean unthrottled, Integer messageSize,
            Boolean randomPayload, Boolean confirms, Integer maxInFlight, Long maxMessages,
            Integer concurrency, Integer prefetch, String handlerTime, Double failureRate,
            String warmup, String runFor,
            Long throughputAtLeast, String p50Below, String p99Below, String p999Below,
            Boolean noMessagesLost) {
    }

    /**
     * What a new load starts from: the library's own defaults, plus the two values a file cannot
     * do without and the drill broker the standing load uses.
     *
     * @return the form
     */
    public static Form defaults() {
        Workload w = Workload.named("standing-load").publishers(p -> p.rate(1000)).build();
        return new Form("standing-load", "${DRILL_BROKER:-amqp://guest:guest@localhost:5772}",
                null, null, w.topology().queue(), null, w.topology().queueType(), true,
                w.publishers().threadCount(), w.publishers().rate(), false,
                w.publishers().payload().size(), w.publishers().payload().isRandom(),
                w.publishers().confirms(), null, null,
                w.consumers().concurrency(), w.consumers().prefetch(), null, null,
                duration(w.warmup()), "1m",
                null, null, null, null, false);
    }

    /**
     * @param form the designer's fields
     * @return the workload file
     */
    public static String toYaml(Form form) {
        Map<String, Object> root = new LinkedHashMap<>();
        put(root, "name", text(form.name()));
        put(root, "broker", text(form.broker()));

        Map<String, Object> topology = new LinkedHashMap<>();
        put(topology, "exchange", text(form.exchange()));
        if (text(form.exchange()) != null) {
            put(topology, "exchangeType", text(form.exchangeType()));
        }
        put(topology, "queue", text(form.queue()));
        put(topology, "routingKey", text(form.routingKey()));
        put(topology, "queueType", text(form.queueType()));
        if (Boolean.FALSE.equals(form.declare())) {
            topology.put("declare", false);
        }
        put(root, "topology", topology);

        Map<String, Object> publishers = new LinkedHashMap<>();
        put(publishers, "threads", form.threads());
        if (Boolean.TRUE.equals(form.unthrottled())) {
            publishers.put("unthrottled", true);
        } else {
            put(publishers, "rate", form.rate());
        }
        put(publishers, "messageSize", form.messageSize());
        if (Boolean.TRUE.equals(form.randomPayload())) {
            publishers.put("randomPayload", true);
        }
        put(publishers, "confirms", form.confirms());
        put(publishers, "maxInFlight", form.maxInFlight());
        put(publishers, "maxMessages", form.maxMessages());
        put(root, "publishers", publishers);

        Map<String, Object> consumers = new LinkedHashMap<>();
        put(consumers, "concurrency", form.concurrency());
        put(consumers, "prefetch", form.prefetch());
        put(consumers, "handlerTime", text(form.handlerTime()));
        put(consumers, "failureRate", form.failureRate());
        put(root, "consumers", consumers);

        put(root, "warmup", text(form.warmup()));
        put(root, "runFor", text(form.runFor()));

        Map<String, Object> expect = new LinkedHashMap<>();
        put(expect, "throughputAtLeast", form.throughputAtLeast());
        put(expect, "p50Below", text(form.p50Below()));
        put(expect, "p99Below", text(form.p99Below()));
        put(expect, "p999Below", text(form.p999Below()));
        if (Boolean.TRUE.equals(form.noMessagesLost())) {
            expect.put("noMessagesLost", true);
        }
        put(root, "expect", expect);

        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a map of strings and numbers did not serialise", e);
        }
    }

    /**
     * What the library makes of a workload file.
     *
     * @param valid whether it parsed
     * @param problems why not, in the library's words
     * @param workloads how many workloads the file holds
     * @param description the library's own summary of what would run, with the password hidden
     */
    public record Check(boolean valid, List<String> problems, int workloads, String description) {
    }

    /**
     * @param yaml a workload file
     * @param environment how {@code ${VAR}} is resolved; the studio's own environment, as the CLI
     * @return the verdict
     */
    public static Check check(String yaml, Function<String, String> environment) {
        if (yaml == null || yaml.isBlank()) {
            return new Check(false, List.of("the workload file is empty"), 0, null);
        }
        try {
            WorkloadFile file = WorkloadFile.parseYaml(yaml, environment);
            String description;
            try {
                description = file.describe();
            } catch (ConfigException e) {
                // No broker is a problem for running it, not for the file's shape.
                return new Check(false, List.of(e.getMessage()), file.size(), null);
            }
            return new Check(true, List.of(), file.size(), description);
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            return new Check(false, List.of(String.valueOf(e.getMessage())), 0, null);
        }
    }

    static String duration(Duration d) {
        long s = d.toSeconds();
        if (s > 0 && s % 3600 == 0) {
            return s / 3600 + "h";
        }
        if (s > 0 && s % 60 == 0) {
            return s / 60 + "m";
        }
        if (d.toMillis() % 1000 != 0) {
            return d.toMillis() + "ms";
        }
        return s + "s";
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value instanceof Map<?, ?> m && m.isEmpty()) {
            return;
        }
        if (value != null) {
            map.put(key, value);
        }
    }
}
