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
package org.acemq.workloads.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Reads a scenario file from disk.
 *
 * <p>This is what makes the studio worth using: what it exports, this reads, and the command line
 * runs it. Without this the studio would be a drawing tool whose output somebody has to translate
 * by hand — and a translation is a place for the two to diverge.
 *
 * <p>Both formats, because both exist for good reasons. The studio writes JSON because that is
 * what its API speaks; a person editing a scenario in a repository writes YAML because comments
 * matter more than machine-readability there.
 */
public final class ScenarioReader {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    private ScenarioReader() {
    }

    /**
     * @param path a scenario file, {@code .json} or {@code .yaml}
     * @return what it describes
     * @throws ScenarioFormatException if it cannot be read
     */
    public static ScenarioFile read(Path path) {
        return read(path, System::getenv);
    }

    static ScenarioFile read(Path path, Function<String, String> environment) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new ScenarioFormatException("cannot read " + path + ": " + e.getMessage(), e);
        }
        return parse(text, isJson(path), environment);
    }

    /**
     * A scenario somebody handed over, rather than one read from disk.
     *
     * <p>Used when the studio opens a file: the bytes arrive over HTTP and the format is decided
     * by the name when there is one and by the first character when there is not.
     *
     * <p><strong>{@code ${VAR}} is left alone here.</strong> Resolving it would read the studio's
     * own environment on behalf of whoever uploaded the file, and hand back the result — a broker
     * URL containing {@code ${AWS_SECRET_ACCESS_KEY}} would come back with the value in it. The
     * placeholder stays a placeholder, and is resolved by the command line at the point where the
     * person running it owns the environment.
     *
     * @param text the file's contents
     * @param fileName what it was called, or null
     * @return what it describes
     * @throws ScenarioFormatException if it cannot be read
     */
    public static ScenarioFile parse(String text, String fileName) {
        return parse(text, looksLikeJson(text, fileName), name -> "${" + name + "}");
    }

    /**
     * @param text the file's contents
     * @param json whether to read it as JSON rather than YAML
     * @param environment where {@code ${VAR}} comes from
     * @return what it describes
     */
    static ScenarioFile parse(String text, boolean json, Function<String, String> environment) {
        String resolved = substitute(text, environment);
        ObjectMapper mapper = mapper(json);
        try {
            ScenarioFile file = mapper.readValue(resolved, ScenarioFile.class);
            if (file.name() == null || file.name().isBlank()) {
                throw new ScenarioFormatException("a scenario needs a name");
            }
            return file;
        } catch (ScenarioFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioFormatException(
                    "this is not a scenario file the studio would have written: " + e.getMessage(), e);
        }
    }

    /**
     * Whether a file is a scenario rather than a single-path workload.
     *
     * <p>Decided by what is in it rather than by a flag somebody has to remember: a scenario
     * describes exchanges, queues and producers, and a workload describes a topology with
     * publishers and consumers. Two shapes, one {@code -f}.
     *
     * @param path the file
     * @return whether it looks like a scenario
     */
    public static boolean isScenario(Path path) {
        try {
            return isScenario(Files.readString(path), isJson(path));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isScenario(String text, boolean json) {
        try {
            JsonNode root = mapper(json).readTree(text);
            if (root == null || !root.isObject()) {
                return false;
            }
            // "producers" is the word that decides it. A workload file says "publishers", and
            // that difference is not cosmetic: one names its sources of load and the other
            // configures the single one it has.
            return root.has("producers") || root.has("queues") || root.has("exchanges");
        } catch (Exception e) {
            return false;
        }
    }

    /** @return what to say about a scenario before running it */
    public static String describe(ScenarioFile file) {
        Scenario scenario = file.toScenario();
        StringBuilder out = new StringBuilder();

        out.append(scenario.name());
        if (!scenario.description().isBlank()) {
            out.append(" — ").append(scenario.description());
        }
        out.append('\n');
        if (file.broker() != null) {
            out.append("broker: ").append(redact(file.broker())).append('\n');
        }
        out.append("warm-up ").append(scenario.warmup().toSeconds())
                .append("s, measuring for ").append(scenario.duration().toSeconds())
                .append("s\n\n");

        for (ExchangeNode exchange : scenario.exchanges()) {
            out.append("  exchange ").append(exchange.name())
                    .append(" (").append(exchange.type()).append(')')
                    .append(exchange.isEnabled() ? "" : "  [off]").append('\n');
        }
        for (QueueNode queue : scenario.queues()) {
            out.append("  queue    ").append(queue.name())
                    .append(" (").append(queue.type().wireName()).append(") ")
                    .append(queue.consumersNode())
                    .append(queue.isEnabled() ? "" : "  [off]").append('\n');
            for (Binding binding : queue.bindings()) {
                out.append("             from ").append(binding.exchange())
                        .append(" on [").append(binding.routingKey()).append("]\n");
            }
            if (!queue.expectations().isEmpty()) {
                out.append("             expects ").append(queue.expectations()).append('\n');
            }
        }
        for (ProducerNode producer : scenario.producers()) {
            out.append("  producer ").append(producer.name())
                    .append(" -> ").append(producer.exchange().isEmpty()
                            ? "(default)" : producer.exchange())
                    .append(' ').append(producer.routingKeys())
                    .append(producer.isUnthrottled() ? " unthrottled"
                            : " at " + producer.rate() + "/s")
                    .append(", ").append(producer.messageSize()).append(" bytes")
                    .append(producer.isEnabled() ? "" : "  [off]").append('\n');
            if (!producer.expectations().isEmpty()) {
                out.append("             expects ").append(producer.expectations()).append('\n');
            }
        }

        for (String problem : scenario.problems()) {
            out.append("\n  PROBLEM  ").append(problem);
        }
        for (String warning : scenario.warnings()) {
            out.append("\n  warning  ").append(warning);
        }
        if (!scenario.problems().isEmpty() || !scenario.warnings().isEmpty()) {
            out.append('\n');
        }
        return out.toString();
    }

    /** @param url a broker URL @return the same URL with the password taken out */
    public static String redact(String url) {
        return url == null ? null : url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }

    private static ObjectMapper mapper(boolean json) {
        ObjectMapper mapper = json ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
        // A misspelled field is a scenario that does something other than what was intended, and
        // finding out from a chart is worse than finding out from a message.
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
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
                    throw new ScenarioFormatException("the scenario file refers to ${" + name
                            + "}, which is not set. Export it, or write ${" + name + ":-default}."
                            + " Secrets belong in the environment rather than in a file that gets"
                            + " committed.");
                }
                value = fallback;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isJson(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /**
     * @param text the file's contents
     * @param fileName what it was called, or null
     * @return whether to read it as JSON
     */
    private static boolean looksLikeJson(String text, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) {
                return true;
            }
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                return false;
            }
        }
        // A browser drag-and-drop arrives without a usable name often enough to be worth deciding
        // from the content: JSON starts with a brace and YAML does not.
        return text.stripLeading().startsWith("{");
    }

    /** A scenario file that cannot be read, with what is wrong with it. */
    public static class ScenarioFormatException extends RuntimeException {

        public ScenarioFormatException(String message) {
            super(message);
        }

        public ScenarioFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
