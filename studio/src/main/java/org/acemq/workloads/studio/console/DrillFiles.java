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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the workspace's drill scripts leave on disk: standing-load sample lines and soak reports.
 *
 * <p>Both are read, never written. Soak reports are the library's endurance command's JSON when
 * there is one, and the old script's markdown otherwise. The sample lines are the {@code --emit-samples} format every language's standing
 * load writes, one JSON object a second; anything else in the file is the load's own output, such
 * as a stack trace, and is shown as an event rather than dropped.
 */
final class DrillFiles {

    private static final int TAIL_BYTES = 96 * 1024;
    private static final Pattern NAME = Pattern.compile("workload-([A-Za-z0-9_.-]+)\\.jsonl");

    private DrillFiles() {
    }

    /** One standing load, read from its sample file. */
    static Map<String, Object> client(Path file, ObjectMapper json) throws IOException {
        Matcher m = NAME.matcher(file.getFileName().toString());
        String name = m.matches() ? m.group(1) : file.getFileName().toString();
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        long age = Math.max(0, Instant.now().getEpochSecond() - modified.getEpochSecond());

        List<JsonNode> samples = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        String lastAt = null;
        for (String line : tail(file)) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("{")) {
                try {
                    JsonNode node = json.readTree(t);
                    samples.add(node);
                    lastAt = node.path("at").asText(lastAt);
                    continue;
                } catch (IOException e) {
                    // Half a line at the start of the tail, or the load's own output: an event.
                }
            }
            String lower = t.toLowerCase(java.util.Locale.ROOT);
            String level = lower.contains("error") || lower.contains("exception")
                    || lower.contains("invalid") || lower.contains("failed") ? "w" : "i";
            events.add(event(lastAt, level, name, t.length() > 240 ? t.substring(0, 240) + "…" : t));
        }
        if (events.size() > 6) {
            events = new ArrayList<>(events.subList(events.size() - 6, events.size()));
        }

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", "drill:" + name);
        client.put("name", name);
        client.put("source", "drill");
        client.put("file", file.getFileName().toString());
        client.put("ageSeconds", age);
        boolean live = age <= 10 && !samples.isEmpty();
        JsonNode last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        client.put("live", live);
        client.put("state", !live ? "stopped"
                : last.path("blocked").asBoolean(false) ? "blocked" : "moving");
        if (last != null) {
            client.put("last", counters(last));
        }
        client.put("history", history(samples));
        client.put("latency", last != null && last.has("endToEndP99Ms")
                ? Map.of("p99Ms", last.get("endToEndP99Ms").asDouble()) : null);
        client.put("events", events);
        return client;
    }

    static Map<String, Object> event(String at, String level, String who, String text) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at);
        e.put("level", level);
        e.put("who", who);
        e.put("text", text);
        return e;
    }

    private static Map<String, Object> counters(JsonNode s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", s.path("at").asText(null));
        for (String k : new String[] {"published", "confirmed", "consumed", "refused", "failed"}) {
            m.put(k, s.path(k).asLong(0));
        }
        m.put("blocked", s.path("blocked").asBoolean(false));
        if (s.has("queueDepth")) {
            m.put("queueDepth", s.get("queueDepth").asLong());
        }
        if (s.has("phase")) {
            m.put("phase", s.get("phase").asText());
        }
        return m;
    }

    /** The last minute: offered and consumed as the load reported them, confirmed as a delta. */
    private static List<Map<String, Object>> history(List<JsonNode> samples) {
        List<Map<String, Object>> out = new ArrayList<>();
        int from = Math.max(1, samples.size() - 60);
        for (int i = from; i < samples.size(); i++) {
            JsonNode prev = samples.get(i - 1);
            JsonNode cur = samples.get(i);
            double dt = (cur.path("elapsedMs").asLong() - prev.path("elapsedMs").asLong()) / 1000.0;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("at", cur.path("at").asText(null));
            p.put("offered", round(cur.path("publishRate").asDouble()));
            p.put("confirmed", dt <= 0 ? 0
                    : round(Math.max(0, cur.path("confirmed").asLong() - prev.path("confirmed").asLong()) / dt));
            p.put("consumed", round(cur.path("consumeRate").asDouble()));
            if (cur.has("endToEndP99Ms")) {
                p.put("p99Ms", cur.get("endToEndP99Ms").asDouble());
            }
            out.add(p);
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static List<String> tail(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(0, len - TAIL_BYTES);
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            List<String> lines = new ArrayList<>(List.of(
                    new String(buf, StandardCharsets.UTF_8).split("\n")));
            if (start > 0 && !lines.isEmpty()) {
                lines.remove(0);
            }
            return lines;
        }
    }

    /** @return the standing-load sample files under the workspace, by name */
    static List<Path> sampleFiles(Path workspace) {
        Path dir = workspace.resolve(".chaos");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- endurance

    private static final Pattern SOAK = Pattern.compile("soak-(\\d{8}-\\d{6})\\.md");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The newest soak report and its readings, or why there is none. */
    static Map<String, Object> latestSoak(Path workspace) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (workspace == null) {
            out.put("available", false);
            out.put("reason", "no drill workspace is configured. Set ACEMQ_STUDIO_DRILL_WORKSPACE"
                    + " to the AMQP libraries workspace whose scripts/soak.sh writes reports/.");
            return out;
        }
        Path reports = workspace.resolve("reports");
        Optional<Path> newest = Optional.empty();
        if (Files.isDirectory(reports)) {
            try (Stream<Path> files = Files.list(reports)) {
                newest = files.filter(p -> SOAK.matcher(p.getFileName().toString()).matches())
                        .max(Comparator.comparing(p -> p.getFileName().toString()));
            }
        }
        if (newest.isEmpty()) {
            out.put("available", false);
            out.put("reason", "no soak report in " + reports + " yet. Run ./scripts/soak.sh in"
                    + " the workspace; this view reads the report it writes.");
            return out;
        }
        Path report = newest.get();
        Matcher idm = SOAK.matcher(report.getFileName().toString());
        idm.matches();
        // The library's endurance command writes a JSON report beside the markdown: the same
        // facts as data, with the readings already in it. Read that when it is there, and the
        // markdown only for a report from before the soak moved into the library.
        Path jsonReport = reports.resolve("soak-" + idm.group(1) + ".json");
        if (Files.isRegularFile(jsonReport)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = JSON.readValue(jsonReport.toFile(), Map.class);
                out.put("available", true);
                out.putAll(doc);
                out.put("source", "framework");
                out.put("report", workspace.relativize(report).toString());
                return out;
            } catch (IOException e) {
                // Half written, or not ours: fall back to the markdown, which is always complete.
            }
        }
        String text = Files.readString(report);
        out.put("available", true);
        out.put("source", "script");
        out.put("id", idm.group(1));
        out.put("report", workspace.relativize(report).toString());
        out.put("verdict", find(text, "(?m)^\\*\\*([A-Z ]+)\\*\\*\\s*$").orElse("UNKNOWN"));
        out.put("summary", find(text, "(?m)^(\\d+ cycles[^\\n]*)$").orElse(null));
        out.put("cycles", find(text, "(?m)^(\\d+) cycles").map(Long::parseLong).orElse(null));
        out.put("settled", find(text, "(?m)^connections while settled: ([^\\n]*)$").orElse(null));
        out.put("afterClose",
                find(text, "(?m)^connections immediately after a close: ([^\\n]*)$").orElse(null));
        out.put("allowances", find(text, "(?m)^Allowances: ([^\\n]*)$").orElse(null));
        out.put("fdSlack", find(text, "descriptors \\+(\\d+)").map(Long::parseLong).orElse(null));
        out.put("threadSlack", find(text, "threads \\+(\\d+)").map(Long::parseLong).orElse(null));
        out.put("rssFactor", find(text, "resident memory ([0-9.]+)x").map(Double::parseDouble)
                .orElse(null));

        List<Map<String, String>> table = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] cells = line.split("\\|");
            if (cells.length >= 9 && !line.contains("---") && !line.contains("library")) {
                Map<String, String> row = new LinkedHashMap<>();
                String[] keys = {"library", "rssBefore", "rssAfter", "fdsBefore", "fdsAfter",
                    "threadsBefore", "threadsAfter", "verdict"};
                for (int i = 0; i < keys.length; i++) {
                    row.put(keys[i], cells[i + 1].strip());
                }
                table.add(row);
            }
        }
        out.put("clients", table);

        String readings = find(text, "Readings: `([^`]+)`").orElse(null);
        out.put("readings", readings);
        Path tsv = readings == null ? null : workspace.resolve(readings).normalize();
        out.put("series", tsv != null && tsv.startsWith(workspace) && Files.isRegularFile(tsv)
                ? series(tsv) : Map.of());
        return out;
    }

    /** Per library, every reading: minutes from the first, RSS in MB, descriptors, threads. */
    private static Map<String, List<double[]>> series(Path tsv) throws IOException {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        long first = -1;
        for (String line : Files.readAllLines(tsv)) {
            String[] c = line.split("\t");
            if (c.length < 6 || !c[0].matches("\\d+")) {
                continue;
            }
            long epoch = Long.parseLong(c[0]);
            if (first < 0) {
                first = epoch;
            }
            try {
                out.computeIfAbsent(c[2], k -> new ArrayList<>()).add(new double[] {
                    round((epoch - first) / 60.0), round(Long.parseLong(c[3]) / 1024.0),
                    Double.parseDouble(c[4]), Double.parseDouble(c[5])});
            } catch (NumberFormatException e) {
                // A reading the script could not take (a dead process) is a gap in the line.
            }
        }
        return out;
    }

    private static Optional<String> find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }
}
