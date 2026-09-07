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
package org.acemq.workloads.studio.run;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Two runs, side by side.
 *
 * <p>The reason every run and every reading is kept: "is this better than last week" is the
 * question people are actually asking, and answering it by opening two reports in two tabs and
 * reading numbers off is how a regression goes unnoticed.
 *
 * <p><strong>Direction is part of the answer.</strong> A latency that went up is worse and a rate
 * that went up is better, and a comparison that reports both as "+12%" leaves the reader to
 * remember which is which — at the moment they are least likely to. So every row says which way is
 * better, and the verdict follows from that rather than from the sign.
 *
 * <p>Nothing here decides whether a difference <em>matters</em>. Two runs of the same
 * configuration differ by a few percent on ordinary hardware, and a tool that called that a
 * regression would be ignored within a week. The threshold below is deliberately wide, and named
 * for what it is: noise, not significance.
 */
public final class Comparison {

    /**
     * How far two numbers can differ before the difference is worth showing as a change.
     *
     * <p>Not a statistical claim. It is the point below which the honest answer is "these two runs
     * are the same run", and saying anything else invites somebody to chase a difference that is
     * the machine rather than the change they made.
     */
    private static final double NOISE = 0.05;

    private Comparison() {
    }

    /**
     * One measurement in both runs.
     *
     * @param node which queue or producer
     * @param metric what was measured
     * @param a the earlier run's number
     * @param b the later run's number
     * @param unit what the numbers are in
     * @param higherIsBetter whether more of it is an improvement
     * @param change the fraction b differs from a, or null when a is zero
     * @param verdict better, worse, same, or missing
     */
    public record Row(String node, String metric, Double a, Double b, String unit,
            boolean higherIsBetter, Double change, String verdict) {
    }

    /**
     * @param a the earlier run's report
     * @param b the later run's report
     * @return every measurement the two have in common
     */
    public static List<Row> of(JsonNode a, JsonNode b) {
        List<Row> rows = new ArrayList<>();

        rows.add(row("run", "published", number(a, "totalPublished"), number(b, "totalPublished"),
                "messages", true));
        rows.add(row("run", "consumed", number(a, "totalConsumed"), number(b, "totalConsumed"),
                "messages", true));

        for (String queue : names(a, b, "queues")) {
            JsonNode left = find(a, "queues", queue);
            JsonNode right = find(b, "queues", queue);
            rows.add(row(queue, "consumed/s", value(left, "consumeRate"),
                    value(right, "consumeRate"), "per second", true));
            // The field names are ReportJson's, which is what the studio stores: already in
            // milliseconds, and named p50/p99/p999 rather than the library writer's p50Ms.
            rows.add(row(queue, "p50", latency(left, "p50"), latency(right, "p50"), "ms", false));
            rows.add(row(queue, "p99", latency(left, "p99"), latency(right, "p99"), "ms", false));
            rows.add(row(queue, "p99.9", latency(left, "p999"), latency(right, "p999"),
                    "ms", false));
        }

        for (String producer : names(a, b, "producers")) {
            JsonNode left = find(a, "producers", producer);
            JsonNode right = find(b, "producers", producer);
            rows.add(row(producer, "offered/s", value(left, "achievedRate"),
                    value(right, "achievedRate"), "per second", true));
            rows.add(row(producer, "failed", value(left, "failed"), value(right, "failed"),
                    "messages", false));
        }

        return rows;
    }

    /**
     * The names on either side, in the order the first run had them.
     *
     * <p>A node in one run and not the other is kept rather than dropped: comparing a scenario
     * with a queue against the same scenario without it is a comparison, and silently leaving the
     * queue out would hide the very thing that changed.
     */
    private static Set<String> names(JsonNode a, JsonNode b, String field) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : a.path(field)) {
            names.add(node.path("name").asText());
        }
        for (JsonNode node : b.path(field)) {
            names.add(node.path("name").asText());
        }
        return names;
    }

    private static JsonNode find(JsonNode report, String field, String name) {
        for (JsonNode node : report.path(field)) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        return null;
    }

    private static Double value(JsonNode node, String field) {
        return node == null || node.path(field).isMissingNode() || node.path(field).isNull()
                ? null : node.path(field).asDouble();
    }

    private static Double latency(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode latency = node.path("endToEnd");
        if (latency.path("count").asLong() == 0) {
            // A queue that handled nothing has no percentiles. Reporting its p99 as zero would
            // make the run that received nothing look like the fastest one.
            return null;
        }
        return value(latency, field);
    }

    private static Double number(JsonNode report, String field) {
        return report.path(field).isMissingNode() ? null : report.path(field).asDouble();
    }

    private static Row row(String node, String metric, Double a, Double b, String unit,
            boolean higherIsBetter) {
        if (a == null || b == null) {
            return new Row(node, metric, a, b, unit, higherIsBetter, null, "missing");
        }
        if (a == 0) {
            // No percentage against zero. Going from nothing to something is a change worth
            // showing and not one worth expressing as a ratio.
            String verdict = b == 0 ? "same" : (higherIsBetter ? "better" : "worse");
            return new Row(node, metric, a, b, unit, higherIsBetter, null, verdict);
        }

        double change = (b - a) / a;
        String verdict;
        if (Math.abs(change) < NOISE) {
            verdict = "same";
        } else if (change > 0) {
            verdict = higherIsBetter ? "better" : "worse";
        } else {
            verdict = higherIsBetter ? "worse" : "better";
        }
        return new Row(node, metric, a, b, unit, higherIsBetter, change, verdict);
    }
}
