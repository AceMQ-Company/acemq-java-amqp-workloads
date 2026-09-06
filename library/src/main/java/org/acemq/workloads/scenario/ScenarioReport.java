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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Severity;

/**
 * What a scenario did, node by node.
 *
 * <p>Same discipline as the single-path report: every finding carries the measurement that
 * produced it, none of them offer advice, and a run that failed to offer its load is reported as
 * <strong>invalid</strong> rather than as a verdict on the broker. Reporting "40,000/s: FAILED"
 * when the generator could only produce 12,000 blames the wrong machine.
 */
public final class ScenarioReport {

    private final Scenario scenario;
    private final Instant startedAt;
    private final Duration duration;
    private final List<ProducerResult> producers;
    private final List<QueueResult> queues;
    private final long blockedNanos;
    private final String blockedReason;
    private final boolean stoppedEarly;
    private final List<Finding> findings;

    ScenarioReport(Scenario scenario, Instant startedAt, Duration duration,
            List<ProducerResult> producers, List<QueueResult> queues,
            long blockedNanos, String blockedReason, boolean stoppedEarly) {
        this.scenario = scenario;
        this.startedAt = startedAt;
        this.duration = duration;
        this.producers = List.copyOf(producers);
        this.queues = List.copyOf(queues);
        this.blockedNanos = blockedNanos;
        this.blockedReason = blockedReason;
        this.stoppedEarly = stoppedEarly;
        this.findings = evaluate();
    }

    /**
     * What one producer managed.
     *
     * @param name the producer
     * @param offeredRate the rate it was asked for, 0 when unthrottled
     * @param published how many it published
     * @param confirmed how many the broker confirmed
     * @param failed how many were refused or errored
     * @param publishLatency time from send to confirm
     * @param sendLag how far behind its own schedule it went out
     */
    public record ProducerResult(
            String name,
            long offeredRate,
            long published,
            long confirmed,
            long failed,
            LatencySummary publishLatency,
            LatencySummary sendLag,
            Expect expectations) {

        /**
         * @param duration how long the measured window was
         * @return messages a second actually offered
         */
        public double achievedRate(Duration duration) {
            double seconds = duration.toNanos() / 1_000_000_000.0;
            return seconds <= 0 ? 0 : published / seconds;
        }
    }

    /**
     * What one queue saw.
     *
     * @param name the queue
     * @param type what kind of queue it was
     * @param consumers how many consumers were on it
     * @param consumed how many messages they handled
     * @param endToEnd latency from when a message was due to when it was handled
     * @param depthAtStart what was waiting when measurement began
     * @param depthAtEnd what was waiting when it ended
     */
    public record QueueResult(
            String name,
            QueueType type,
            int consumers,
            long consumed,
            LatencySummary endToEnd,
            Long depthAtStart,
            Long depthAtEnd,
            Expect expectations) {

        /**
         * Whether this kind of queue keeps messages after they have been read.
         *
         * <p>A stream does: consumers move an offset and nothing is removed, so its "depth" is
         * the length of the log rather than a backlog. Treating that as a backlog reports a
         * stream that kept up perfectly as one whose consumers fell behind, which is exactly
         * backwards and was doing so until this existed.
         *
         * @return whether depth means retention rather than a queue of unhandled work
         */
        public boolean retainsMessages() {
            return type == QueueType.STREAM;
        }

        /**
         * @param duration how long the measured window was
         * @return messages a second handled
         */
        public double consumeRate(Duration duration) {
            double seconds = duration.toNanos() / 1_000_000_000.0;
            return seconds <= 0 ? 0 : consumed / seconds;
        }

        /**
         * @return whether unhandled work accumulated over the measured window
         */
        public boolean grew() {
            if (retainsMessages()) {
                // A stream always "grows": that is what an append-only log does.
                return false;
            }
            return depthAtStart != null && depthAtEnd != null && depthAtEnd > depthAtStart;
        }
    }

    public Scenario scenario() {
        return scenario;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** @return how long the measured window was, which is shorter than asked for if stopped */
    public Duration duration() {
        return duration;
    }

    public List<ProducerResult> producers() {
        return producers;
    }

    public List<QueueResult> queues() {
        return queues;
    }

    public boolean wasStoppedEarly() {
        return stoppedEarly;
    }

    public Duration blockedFor() {
        return Duration.ofNanos(blockedNanos);
    }

    public String blockedReason() {
        return blockedReason;
    }

    public List<Finding> findings() {
        return findings;
    }

    /** @return everything published across every producer */
    public long totalPublished() {
        return producers.stream().mapToLong(ProducerResult::published).sum();
    }

    /** @return everything consumed across every queue */
    public long totalConsumed() {
        return queues.stream().mapToLong(QueueResult::consumed).sum();
    }

    /** @return whether anything makes this run's numbers untrustworthy */
    public boolean isValid() {
        return findings.stream().noneMatch(f -> f.severity() == Severity.INVALID);
    }

    /** @return whether it is valid and nothing failed */
    public boolean passed() {
        return isValid() && findings.stream().noneMatch(f -> f.severity() == Severity.FAILED);
    }

    private List<Finding> evaluate() {
        List<Finding> found = new ArrayList<>();

        for (ProducerResult producer : producers) {
            // The rule that outranks every other: a generator that could not keep to its own
            // schedule did not offer the load, so nothing else in the report is about the broker.
            if (producer.offeredRate() > 0 && producer.sendLag().count() > 0) {
                Duration p99 = producer.sendLag().percentile(99);
                if (p99.toMillis() > 100) {
                    found.add(Finding.of("generator-kept-up:" + producer.name(), Severity.INVALID,
                            "publishes from " + producer.name() + " ran " + p99.toMillis()
                                    + "ms behind their own schedule at p99; the configured rate was "
                                    + producer.offeredRate() + "/s and the achieved rate was "
                                    + Math.round(producer.achievedRate(duration)) + "/s",
                            "the configured load was never offered, so this run does not show what"
                                    + " the broker can take. Raise the producer's threads, lower its"
                                    + " rate, or run the generator somewhere that is not also the"
                                    + " broker, and repeat"));
                }
            }
            if (producer.failed() > 0) {
                found.add(Finding.of("publishes-succeeded:" + producer.name(), Severity.FAILED,
                        producer.name() + " had " + producer.failed() + " publishes refused or"
                                + " errored out of " + producer.published(),
                        "a publish that failed is a message the application would have lost"));
            }
        }

        for (QueueResult queue : queues) {
            // grew() is already false for a stream, so this rule cannot fire on one. Said here
            // as well because the rule reads as if it were about any queue that got longer.
            if (queue.consumers() > 0 && queue.grew()) {
                found.add(Finding.of("consumers-kept-up:" + queue.name(), Severity.WARNING,
                        queue.name() + " grew from " + queue.depthAtStart() + " to "
                                + queue.depthAtEnd() + " messages over the run",
                        "its consumers took less than was published to it for the whole window,"
                                + " so the backlog was still growing when the run ended"));
            }
        }

        found.addAll(objectives());

        if (blockedNanos > 0) {
            found.add(Finding.of("broker-not-blocked", Severity.INVALID,
                    "the broker blocked publishers for "
                            + Duration.ofNanos(blockedNanos).toMillis() + "ms"
                            + (blockedReason == null ? "" : " (" + blockedReason + ")"),
                    "a blocked connection means a resource alarm, so what was measured is the"
                            + " alarm rather than the broker's capacity"));
        }

        if (stoppedEarly) {
            found.add(Finding.of("run-was-stopped", Severity.WARNING,
                    "this run was stopped after " + duration.toSeconds()
                            + "s rather than running to its end",
                    "the numbers describe the window that was measured, which may be too short to"
                            + " be representative"));
        } else if (duration.toSeconds() < 30) {
            found.add(Finding.of("run-was-long-enough", Severity.WARNING,
                    "the measured window was " + duration.toSeconds() + "s",
                    "a short window is dominated by whatever happened to be going on during it"));
        }

        return List.copyOf(found);
    }

    /**
     * What was asked for, and whether it held.
     *
     * <p>Kept apart from the diagnostic rules on purpose. A rule says something about the run
     * that is true whether or not anybody wanted to know; an objective is a question somebody
     * asked, and the answer is what a pipeline acts on.
     *
     * @return one finding per objective that was not met
     */
    private List<Finding> objectives() {
        List<Finding> found = new ArrayList<>();

        for (QueueResult queue : queues) {
            Expect expect = queue.expectations();
            if (expect == null || expect.isEmpty()) {
                continue;
            }
            LatencySummary latency = queue.endToEnd();

            missed(found, queue.name(), "p50", expect.p50BelowValue(),
                    latency.count() == 0 ? null : latency.p50());
            missed(found, queue.name(), "p99", expect.p99BelowValue(),
                    latency.count() == 0 ? null : latency.p99());
            missed(found, queue.name(), "p99.9", expect.p999BelowValue(),
                    latency.count() == 0 ? null : latency.p999());

            if (expect.consumeRateAtLeastValue() != null) {
                double achieved = queue.consumeRate(duration);
                if (achieved < expect.consumeRateAtLeastValue()) {
                    found.add(Finding.of("expected-consume-rate:" + queue.name(), Severity.FAILED,
                            String.format("%s handled %,.0f/s, and was asked for at least %,d/s",
                                    queue.name(), achieved, expect.consumeRateAtLeastValue()),
                            "the consumers on this queue did not keep up with what was asked of"
                                    + " them, whatever the broker was capable of"));
                }
            }

            if (expect.requiresNoBacklog() && queue.grew()) {
                found.add(Finding.of("expected-no-backlog:" + queue.name(), Severity.FAILED,
                        queue.name() + " went from " + queue.depthAtStart() + " to "
                                + queue.depthAtEnd() + " messages, and was asked not to grow",
                        "work arrived faster than it was handled for the whole window"));
            }
        }

        for (ProducerResult producer : producers) {
            Expect expect = producer.expectations();
            if (expect == null || expect.isEmpty()) {
                continue;
            }

            if (expect.requiresNoFailures() && producer.failed() > 0) {
                found.add(Finding.of("expected-no-failures:" + producer.name(), Severity.FAILED,
                        producer.name() + " had " + producer.failed()
                                + " publishes fail, and was asked for none",
                        "a publish that failed is a message the application would have lost"));
            }

            double achieved = producer.achievedRate(duration);
            if (expect.achievedRateAtLeastValue() != null
                    && achieved < expect.achievedRateAtLeastValue()) {
                found.add(Finding.of("expected-offered-rate:" + producer.name(), Severity.FAILED,
                        String.format("%s offered %,.0f/s, and was asked for at least %,d/s",
                                producer.name(), achieved, expect.achievedRateAtLeastValue()),
                        "the load this run was supposed to apply was never applied"));
            }

            if (expect.withinPercentOfOfferedValue() != null && producer.offeredRate() > 0) {
                double floor = producer.offeredRate()
                        * (100 - expect.withinPercentOfOfferedValue()) / 100.0;
                if (achieved < floor) {
                    found.add(Finding.of("expected-within-percent:" + producer.name(),
                            Severity.FAILED,
                            String.format("%s offered %,.0f/s of the %,d/s it was configured for,"
                                            + " which is short of the %d%% allowed",
                                    producer.name(), achieved, producer.offeredRate(),
                                    expect.withinPercentOfOfferedValue()),
                            "the generator did not apply the load, so this run describes the"
                                    + " generator rather than the broker"));
                }
            }
        }

        return found;
    }

    private void missed(List<Finding> found, String queue, String percentile, Duration limit,
            Duration measured) {
        if (limit == null) {
            return;
        }
        if (measured == null) {
            found.add(Finding.of("expected-" + percentile + ":" + queue, Severity.FAILED,
                    queue + " handled nothing, so its " + percentile + " cannot be compared with"
                            + " the " + limit.toMillis() + "ms it was asked for",
                    "an expectation about latency on a queue that received nothing is not met;"
                            + " it is unanswerable, which is not the same as passing"));
            return;
        }
        if (measured.compareTo(limit) > 0) {
            found.add(Finding.of("expected-" + percentile + ":" + queue, Severity.FAILED,
                    String.format("%s %s was %.1fms, and was asked for under %dms",
                            queue, percentile, measured.toNanos() / 1_000_000.0, limit.toMillis()),
                    "the queue answered, and answered more slowly than this run required"));
        }
    }

    /** @return the report as text, for a terminal or a log */
    public String format() {
        StringBuilder out = new StringBuilder();
        out.append(scenario.name()).append(" -- ").append(duration.toSeconds()).append("s\n");
        if (!scenario.description().isBlank()) {
            out.append(scenario.description()).append('\n');
        }
        out.append('\n');

        out.append("producers\n");
        for (ProducerResult producer : producers) {
            out.append(String.format("  %-20s %,10.0f/s offered  %,10.0f/s achieved  %,d failed%n",
                    producer.name(),
                    (double) producer.offeredRate(),
                    producer.achievedRate(duration),
                    producer.failed()));
        }

        out.append("\nqueues\n");
        for (QueueResult queue : queues) {
            out.append(String.format("  %-20s %-8s %2d consumers  %,10.0f/s  p99 %s  depth %s%n",
                    queue.name(),
                    queue.type().wireName(),
                    queue.consumers(),
                    queue.consumeRate(duration),
                    queue.endToEnd().count() == 0 ? "--" : queue.endToEnd().percentile(99).toMillis() + "ms",
                    queue.depthAtEnd() == null ? "--"
                            : queue.depthAtEnd() + (queue.retainsMessages() ? " retained" : "")));
        }

        if (!findings.isEmpty()) {
            out.append('\n');
            for (Finding finding : findings) {
                out.append('[').append(finding.severity()).append("] ")
                        .append(finding.rule()).append('\n')
                        .append("    observed:  ").append(finding.observation()).append('\n')
                        .append("    means:     ").append(finding.implication()).append('\n');
            }
        }

        out.append("\n  result: ")
                .append(!isValid() ? "INVALID -- this run did not measure what it was asked to"
                        : passed() ? "PASSED" : "FAILED")
                .append('\n');
        return out.toString();
    }

    @Override
    public String toString() {
        return "ScenarioReport{" + scenario.name() + ", " + totalPublished() + " published, "
                + totalConsumed() + " consumed, " + (passed() ? "passed" : "not passed") + "}";
    }
}
