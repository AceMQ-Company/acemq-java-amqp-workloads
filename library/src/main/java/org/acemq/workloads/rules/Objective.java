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
import java.util.Optional;

import org.acemq.workloads.WorkloadReport;

/**
 * What the run has to achieve to count as a pass.
 *
 * <pre>{@code
 * .expect(Objective.throughputAtLeast(300_000))
 * .expect(Objective.p99Below(Duration.ofMillis(50)))
 * }</pre>
 *
 * <p>This is the "we need 300,000 a second, let us test" case, expressed so a build can fail on
 * it.
 *
 * <h2>An objective is only judged on a valid run</h2>
 *
 * <p>If the generator could not offer the rate, or the broker was blocked by a resource alarm
 * for most of the run, then the throughput measured is not the broker's answer to the question.
 * Objectives are still evaluated, but the report's {@link WorkloadReport#isValid()} is false and
 * {@link WorkloadReport#passed()} is false regardless — a run that did not measure the thing
 * cannot pass, and it must not be reported as the broker failing either.
 */
public final class Objective {

    private Objective() {
    }

    /**
     * @param messagesPerSecond the end-to-end rate that must be sustained. Measured on messages
     *     that were <em>consumed</em>, not published, because a publish rate the consumers never
     *     matched is a queue filling up rather than a system running
     * @return the rule
     */
    public static Rule throughputAtLeast(long messagesPerSecond) {
        return report -> {
            double achieved = report.consumersEnabled()
                    ? report.consumeRate()
                    : report.achievedPublishRate();
            String what = report.consumersEnabled() ? "end-to-end" : "publish";

            if (achieved >= messagesPerSecond) {
                return Optional.of(Finding.of("throughput>=" + messagesPerSecond, Severity.INFO,
                        String.format("sustained %,.0f msg/s %s over %s",
                                achieved, what, human(report.duration())),
                        "the objective was met"));
            }
            return Optional.of(Finding.of("throughput>=" + messagesPerSecond, Severity.FAILED,
                    String.format("sustained %,.0f msg/s %s, against an objective of %,d",
                            achieved, what, messagesPerSecond),
                    String.format("this configuration delivers %.0f%% of the required rate",
                            100.0 * achieved / messagesPerSecond),
                    String.format("published=%,d consumed=%,d over %s",
                            report.published(), report.consumed(), human(report.duration()))));
        };
    }

    /**
     * @param budget the 99th percentile end-to-end latency must be under this
     * @return the rule
     */
    public static Rule p99Below(Duration budget) {
        return percentileBelow(99.0, budget);
    }

    /**
     * @param percentile 50, 90, 99, 99.9 or 99.99
     * @param budget the ceiling
     * @return the rule
     */
    public static Rule percentileBelow(double percentile, Duration budget) {
        String name = "p" + trim(percentile) + "<" + human(budget);
        return report -> {
            if (report.endToEnd().isEmpty()) {
                return Optional.of(Finding.of(name, Severity.INVALID,
                        "no end-to-end latency was recorded",
                        "a latency objective needs consumers; this run had none, so there is"
                                + " nothing to measure the objective against"));
            }
            Duration actual = report.endToEnd().percentile(percentile);
            if (actual.compareTo(budget) <= 0) {
                return Optional.of(Finding.of(name, Severity.INFO,
                        "p" + trim(percentile) + " was " + human(actual),
                        "within the " + human(budget) + " budget"));
            }
            return Optional.of(Finding.of(name, Severity.FAILED,
                    "p" + trim(percentile) + " was " + human(actual)
                            + ", against a budget of " + human(budget),
                    String.format("one message in %s took longer than the budget allows",
                            oneIn(percentile)),
                    "p50=" + human(report.endToEnd().p50())
                            + " p99=" + human(report.endToEnd().p99())
                            + " max=" + human(report.endToEnd().max())));
        };
    }

    /**
     * Every published message must arrive.
     *
     * <p>Note this compares counts. It detects loss and it does not detect duplication, which
     * needs the sequence numbers and is a different measurement.
     *
     * @return the rule
     */
    public static Rule noMessagesLost() {
        return report -> {
            if (!report.consumersEnabled()) {
                return Optional.empty();
            }
            long missing = report.confirmed() - report.consumed();
            if (missing <= 0) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("no-messages-lost", Severity.FAILED,
                    String.format("%,d messages were confirmed by the broker and %,d were"
                            + " consumed, leaving %,d unaccounted for",
                            report.confirmed(), report.consumed(), missing),
                    "either they are still in the queue at the end of the run, or they were lost."
                            + " The queue depth in this report distinguishes the two",
                    "queue depth at the end: " + report.queueDepthAtEnd()
                            .map(String::valueOf).orElse("not read")));
        };
    }

    private static String trim(double percentile) {
        return percentile == Math.rint(percentile)
                ? String.valueOf((long) percentile)
                : String.valueOf(percentile);
    }

    private static String oneIn(double percentile) {
        double remainder = 100.0 - percentile;
        return remainder <= 0 ? "all" : String.format("%,.0f", 100.0 / remainder);
    }

    static String human(Duration d) {
        long nanos = d.toNanos();
        if (nanos < 1_000) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000) {
            return (nanos / 1_000) + "us";
        }
        if (nanos < 10_000_000_000L) {
            return String.format("%.1fms", nanos / 1_000_000.0);
        }
        return d.toSeconds() + "s";
    }
}
