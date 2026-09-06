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
package org.acemq.workloads;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Severity;

/**
 * What one run measured, and what the rules made of it.
 *
 * <p>Read {@link #isValid()} before anything else. A report whose run did not offer the load it
 * was configured for, or whose broker was blocked by a resource alarm for half the time, still
 * has a full set of numbers — and those numbers describe the client or the alarm rather than the
 * broker.
 */
public final class WorkloadReport {

    private final Workload spec;
    private final Instant startedAt;
    private final Duration duration;
    private final long published;
    private final long confirmed;
    private final long failed;
    private final long consumed;
    private final LatencySummary endToEnd;
    private final LatencySummary publishLatency;
    private final LatencySummary sendLag;
    private final Long queueDepthAtEnd;
    private final long blockedNanos;
    private final String blockedReason;
    private final List<Finding> findings;

    WorkloadReport(Workload spec, Instant startedAt, Duration duration,
            long published, long confirmed, long failed, long consumed,
            LatencySummary endToEnd, LatencySummary publishLatency, LatencySummary sendLag,
            Long queueDepthAtEnd, long blockedNanos, String blockedReason) {
        this.spec = spec;
        this.startedAt = startedAt;
        this.duration = duration;
        this.published = published;
        this.confirmed = confirmed;
        this.failed = failed;
        this.consumed = consumed;
        this.endToEnd = endToEnd;
        this.publishLatency = publishLatency;
        this.sendLag = sendLag;
        this.queueDepthAtEnd = queueDepthAtEnd;
        this.blockedNanos = blockedNanos;
        this.blockedReason = blockedReason;
        this.findings = evaluate(spec.rules());
    }

    private List<Finding> evaluate(List<Rule> rules) {
        List<Finding> found = new ArrayList<>();
        for (Rule rule : rules) {
            rule.check(this).ifPresent(found::add);
        }
        // Worst first, so whoever prints one line prints the thing that matters.
        found.sort(Comparator.comparing(Finding::severity));
        return found;
    }

    public Workload spec() {
        return spec;
    }

    public String name() {
        return spec.name();
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** @return the measured window, which excludes warm-up */
    public Duration duration() {
        return duration;
    }

    /** @return how many messages were handed to the broker */
    public long published() {
        return published;
    }

    /** @return how many the broker confirmed. Equal to {@link #published()} when confirms are off */
    public long confirmed() {
        return confirmed;
    }

    /** @return how many publishes were refused or errored */
    public long failed() {
        return failed;
    }

    /** @return how many messages the consumers processed */
    public long consumed() {
        return consumed;
    }

    /** @return the rate the workload was configured to offer, or 0 when unthrottled */
    public long offeredRate() {
        return spec.publishers().rate();
    }

    /** @return messages published per second over the measured window */
    public double achievedPublishRate() {
        return rate(published);
    }

    /** @return messages consumed per second over the measured window */
    public double consumeRate() {
        return rate(consumed);
    }

    private double rate(long count) {
        double seconds = duration.toNanos() / 1_000_000_000.0;
        return seconds <= 0 ? 0 : count / seconds;
    }

    /**
     * @return latency from when each message was <em>due</em> to when a consumer received it.
     *     Empty when the workload had no consumers
     */
    public LatencySummary endToEnd() {
        return endToEnd;
    }

    /** @return how long the publish call itself took, including the confirm when they are on */
    public LatencySummary publishLatency() {
        return publishLatency;
    }

    /**
     * @return how far behind its own schedule each publish went out. This is the measurement
     *     that says whether the configured rate was actually offered, and a large value here
     *     invalidates every other number in the report
     */
    public LatencySummary sendLag() {
        return sendLag;
    }

    /** @return the queue's depth when the run finished, if it could be read */
    public Optional<Long> queueDepthAtEnd() {
        return Optional.ofNullable(queueDepthAtEnd);
    }

    /** @return whether the connection was ever blocked by a broker resource alarm */
    public boolean wasBlocked() {
        return blockedNanos > 0;
    }

    /** @return how long the connection spent blocked */
    public long blockedNanos() {
        return blockedNanos;
    }

    public Optional<String> blockedReason() {
        return Optional.ofNullable(blockedReason);
    }

    public boolean consumersEnabled() {
        return spec.consumers().isEnabled();
    }

    /** @return everything the rules noticed, worst first */
    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    /**
     * @return whether this run measured what it set out to measure. False when a rule returned
     *     {@link Severity#INVALID} — the numbers are still here, and no conclusion may be drawn
     *     from them
     */
    public boolean isValid() {
        return findings.stream().noneMatch(Finding::invalidatesRun);
    }

    /**
     * @return whether the run is valid <em>and</em> nothing failed. An invalid run never passes,
     *     because a measurement of the wrong thing cannot meet an objective about the right one
     */
    public boolean passed() {
        return isValid() && findings.stream().noneMatch(f -> f.severity() == Severity.FAILED);
    }

    /** @return the findings that failed or invalidated the run */
    public List<Finding> problems() {
        List<Finding> problems = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.severity() == Severity.INVALID || finding.severity() == Severity.FAILED) {
                problems.add(finding);
            }
        }
        return problems;
    }

    /** @return the whole report, formatted for a terminal */
    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("workload: ").append(name()).append('\n');
        out.append("  ").append(spec.topology()).append('\n');
        out.append("  ").append(spec.publishers()).append('\n');
        out.append("  ").append(spec.consumers()).append('\n');
        out.append('\n');

        out.append(String.format("  window        %s%n", human(duration)));
        out.append(String.format("  published     %,d  (%,.0f/s%s)%n", published,
                achievedPublishRate(),
                offeredRate() > 0 ? String.format(", offered %,d/s", offeredRate()) : ""));
        if (failed > 0) {
            out.append(String.format("  failed        %,d%n", failed));
        }
        if (consumersEnabled()) {
            out.append(String.format("  consumed      %,d  (%,.0f/s)%n", consumed, consumeRate()));
        }
        queueDepthAtEnd().ifPresent(depth ->
                out.append(String.format("  queue at end  %,d%n", depth)));
        out.append('\n');

        out.append("  ").append(endToEnd.format()).append('\n');
        out.append("  ").append(publishLatency.format()).append('\n');
        if (!sendLag.isEmpty()) {
            out.append("  ").append(sendLag.format()).append('\n');
        }
        out.append('\n');

        if (findings.isEmpty()) {
            out.append("  no findings\n");
        } else {
            for (Finding finding : findings) {
                out.append("  ").append(finding.format().replace("\n", "\n  ")).append('\n');
            }
        }
        out.append('\n');
        out.append("  result: ")
                .append(!isValid() ? "INVALID — this run did not measure what it was asked to"
                        : passed() ? "PASSED" : "FAILED")
                .append('\n');
        return out.toString();
    }

    private static String human(Duration d) {
        long seconds = d.toSeconds();
        return seconds >= 60 ? (seconds / 60) + "m" + (seconds % 60) + "s" : seconds + "s";
    }

    @Override
    public String toString() {
        return "WorkloadReport{" + name() + ": " + String.format("%,.0f", achievedPublishRate())
                + "/s published, " + (isValid() ? (passed() ? "passed" : "failed") : "INVALID") + "}";
    }
}
