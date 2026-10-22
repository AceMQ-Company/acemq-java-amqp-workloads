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
package org.acemq.workloads.rules;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.acemq.workloads.WorkloadReport;
import org.acemq.workloads.metrics.LatencySummary;

/**
 * A rule written as a threshold on one number in the report — the shape of the admin library's
 * alerts, so a rule reads the same whether it watches a broker or judges a run.
 *
 * <pre>{@code
 * Rule backlog = Threshold.named("backlog-stays-small")
 *         .on("queueDepthAtEnd").above(1_000)
 *         .severity(Severity.FAILED)
 *         .because("a queue that ends deep was filling, whatever the rates say");
 *
 * Rule tail = Threshold.named("tail")
 *         .on("endToEnd.p999").above(Duration.ofMillis(200))
 *         .because("one message in a thousand is a user every few seconds");
 * }</pre>
 *
 * <p>The same rule in a workload file:
 *
 * <pre>{@code
 * rules:
 *   - name: backlog-stays-small
 *     metric: queueDepthAtEnd
 *     above: 1000
 *     severity: failed
 *     because: a queue that ends deep was filling, whatever the rates say
 * }</pre>
 *
 * <p>A metric the run did not produce — a latency with no consumers, a depth that could not be
 * read — never fires: there is nothing to compare. Latency metrics are compared in nanoseconds and
 * take a {@link Duration}; {@code errorRate} is a fraction from 0 to 1.
 */
public final class Threshold {

    private static final Map<String, ToDoubleFunction<WorkloadReport>> METRICS = metrics();

    private final String name;
    private String metric;
    private Double above;
    private Double below;
    private Severity severity = Severity.FAILED;

    private Threshold(String name) {
        this.name = name;
    }

    /** @param name what the finding is called in the report
     *  @return a threshold to configure */
    public static Threshold named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a rule needs a name: it is what the report calls it");
        }
        return new Threshold(name);
    }

    /** @return every metric a threshold can be put on, in a stable order */
    public static List<String> metricNames() {
        return List.copyOf(METRICS.keySet());
    }

    /** @param metric one of {@link #metricNames()}
     *  @return whether it is a latency, compared as a duration */
    public static boolean isLatency(String metric) {
        return metric.contains(".");
    }

    /** @param metric one of {@link #metricNames()}
     *  @return this */
    public Threshold on(String metric) {
        if (!METRICS.containsKey(metric)) {
            throw new IllegalArgumentException("unknown metric '" + metric + "'. Known: "
                    + String.join(", ", METRICS.keySet()));
        }
        this.metric = metric;
        return this;
    }

    /** @param limit fires when the metric is above this
     *  @return this */
    public Threshold above(double limit) {
        this.above = limit;
        this.below = null;
        return this;
    }

    /** @param limit fires when the metric is below this
     *  @return this */
    public Threshold below(double limit) {
        this.below = limit;
        this.above = null;
        return this;
    }

    /** @param limit for a latency metric: fires when it is above this
     *  @return this */
    public Threshold above(Duration limit) {
        return above((double) latencyLimit(limit));
    }

    /** @param limit for a latency metric: fires when it is below this
     *  @return this */
    public Threshold below(Duration limit) {
        return below((double) latencyLimit(limit));
    }

    private long latencyLimit(Duration limit) {
        if (metric == null || !isLatency(metric)) {
            throw new IllegalArgumentException("a duration limit needs a latency metric, such as"
                    + " endToEnd.p99; '" + metric + "' is not one");
        }
        return limit.toNanos();
    }

    /** @param severity how bad it is when it fires; FAILED unless told otherwise
     *  @return this */
    public Threshold severity(Severity severity) {
        this.severity = Objects.requireNonNull(severity, "severity");
        return this;
    }

    /**
     * @param reason what crossing the threshold means, printed as the finding's implication
     * @return the rule
     */
    public Rule because(String reason) {
        if (metric == null) {
            throw new IllegalArgumentException("rule '" + name + "' has no metric");
        }
        if (above == null && below == null) {
            throw new IllegalArgumentException("rule '" + name + "' needs 'above' or 'below'");
        }
        String metric = this.metric;
        boolean isAbove = above != null;
        double limit = isAbove ? above : below;
        Severity severity = this.severity;
        String implication = reason == null || reason.isBlank()
                ? "the threshold set for this run was crossed" : reason;
        ToDoubleFunction<WorkloadReport> read = METRICS.get(metric);
        return report -> {
            double value = read.applyAsDouble(report);
            if (Double.isNaN(value) || (isAbove ? value <= limit : value >= limit)) {
                return Optional.empty();
            }
            return Optional.of(Finding.of(name, severity,
                    String.format("%s was %s, %s %s", metric, show(metric, value),
                            isAbove ? "above" : "below", show(metric, limit)),
                    implication));
        };
    }

    private static String show(String metric, double value) {
        if (isLatency(metric)) {
            return Objective.human(Duration.ofNanos((long) value));
        }
        if ("errorRate".equals(metric)) {
            return String.format("%.2f%%", value * 100);
        }
        return value == Math.rint(value) ? String.format("%,.0f", value)
                : String.format("%,.2f", value);
    }

    private static Map<String, ToDoubleFunction<WorkloadReport>> metrics() {
        Map<String, ToDoubleFunction<WorkloadReport>> m = new LinkedHashMap<>();
        m.put("offeredRate", WorkloadReport::offeredRate);
        m.put("publishRate", WorkloadReport::achievedPublishRate);
        m.put("consumeRate", r -> r.consumersEnabled() ? r.consumeRate() : Double.NaN);
        m.put("published", WorkloadReport::published);
        m.put("confirmed", WorkloadReport::confirmed);
        m.put("consumed", r -> r.consumersEnabled() ? r.consumed() : Double.NaN);
        m.put("failed", WorkloadReport::failed);
        m.put("errorRate", r -> {
            long attempts = r.published() + r.failed();
            return attempts == 0 ? Double.NaN : (double) r.failed() / attempts;
        });
        m.put("backlog", r -> r.consumersEnabled() ? r.published() - r.consumed() : Double.NaN);
        m.put("queueDepthAtEnd", r -> r.queueDepthAtEnd().map(Long::doubleValue).orElse(Double.NaN));
        m.put("blockedSeconds", r -> r.blockedNanos() / 1_000_000_000.0);
        // The patterns' counts. A run that did not use the pattern has no such count, and a rule
        // on it never fires, the same as a latency from a run with no consumers.
        for (String key : java.util.List.of("retried", "recovered", "deadLettered",
                "claimChecked", "claimMissing", "outboxPeak", "outboxLeft", "outboxFull",
                "relayGaveUp", "sagaCompleted", "sagaCompensated", "sagaUnresolved",
                "pipelineCompleted", "endedEarly")) {
            m.put(key, r -> r.patterns().containsKey(key) ? r.patterns().get(key) : Double.NaN);
        }
        latencies(m, "endToEnd", WorkloadReport::endToEnd);
        latencies(m, "publish", WorkloadReport::publishLatency);
        latencies(m, "sendLag", WorkloadReport::sendLag);
        return m;
    }

    private static void latencies(Map<String, ToDoubleFunction<WorkloadReport>> m, String prefix,
            Function<WorkloadReport, LatencySummary> summary) {
        Map<String, Function<LatencySummary, Duration>> picks = new LinkedHashMap<>();
        picks.put("p50", LatencySummary::p50);
        picks.put("p90", LatencySummary::p90);
        picks.put("p99", LatencySummary::p99);
        picks.put("p999", LatencySummary::p999);
        picks.put("max", LatencySummary::max);
        picks.forEach((key, pick) -> m.put(prefix + "." + key, r -> {
            LatencySummary s = summary.apply(r);
            return s.isEmpty() ? Double.NaN : pick.apply(s).toNanos();
        }));
    }
}
