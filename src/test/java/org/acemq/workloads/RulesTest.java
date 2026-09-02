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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Objective;
import org.acemq.workloads.rules.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("the rules")
class RulesTest {

    /** A report built from numbers, so a rule can be checked without a broker. */
    private static WorkloadReport report(Workload workload, long published, long consumed,
            LatencySummary endToEnd, LatencySummary sendLag, long blockedNanos, Duration duration) {
        return new WorkloadReport(workload, Instant.now(), duration,
                published, published, 0, consumed,
                endToEnd, LatencySummary.empty("publish"), sendLag,
                null, blockedNanos, blockedNanos > 0 ? "resource alarm" : null);
    }

    private static LatencySummary latencies(long... nanos) {
        LatencyRecorder recorder = new LatencyRecorder("test");
        for (long n : nanos) {
            recorder.record(n);
        }
        return recorder.summary();
    }

    private static LatencySummary constant(long nanos, int count) {
        LatencyRecorder recorder = new LatencyRecorder("test");
        for (int i = 0; i < count; i++) {
            recorder.record(nanos);
        }
        return recorder.summary();
    }

    private static Workload workload(long rate, int threads) {
        return Workload.named("test")
                .publishers(p -> p.rate(rate).threads(threads))
                .consumers(c -> c.concurrency(4))
                .runFor(Duration.ofMinutes(1))
                .build();
    }

    @Nested
    @DisplayName("validity")
    class Validity {

        @Test
        @DisplayName("a generator that fell behind its schedule invalidates the run")
        void generatorSaturated() {
            // The 300,000/s case: the client could not offer it, and reporting that as the
            // broker failing would blame the wrong machine.
            WorkloadReport report = report(workload(300_000, 1), 90_000, 90_000,
                    constant(Duration.ofMillis(5).toNanos(), 1000),
                    constant(Duration.ofSeconds(4).toNanos(), 1000),
                    0, Duration.ofMinutes(1));

            assertThat(report.isValid()).isFalse();
            assertThat(report.passed()).isFalse();

            Finding finding = report.problems().get(0);
            assertThat(finding.severity()).isEqualTo(Severity.INVALID);
            assertThat(finding.rule()).isEqualTo("generator-kept-up");
            assertThat(finding.observation()).contains("behind their own schedule");
            assertThat(finding.implication()).contains("was never offered");
        }

        @Test
        @DisplayName("a run that kept to its schedule is valid")
        void generatorKeptUp() {
            WorkloadReport report = report(workload(1_000, 2), 60_000, 60_000,
                    constant(Duration.ofMillis(5).toNanos(), 1000),
                    constant(200_000, 1000),
                    0, Duration.ofMinutes(1));

            assertThat(report.isValid()).isTrue();
        }

        @Test
        @DisplayName("a broker blocked by a resource alarm invalidates the run")
        void brokerBlocked() {
            WorkloadReport report = report(workload(1_000, 2), 10_000, 10_000,
                    constant(Duration.ofMillis(5).toNanos(), 1000),
                    constant(1000, 1000),
                    Duration.ofSeconds(26).toNanos(), Duration.ofMinutes(1));

            assertThat(report.isValid()).isFalse();
            assertThat(report.problems())
                    .anySatisfy(f -> assertThat(f.rule()).isEqualTo("broker-not-blocked"));
            assertThat(report.problems())
                    .anySatisfy(f -> assertThat(f.observation()).contains("43%"));
        }

        @Test
        @DisplayName("an invalid run cannot pass, even when every objective was met")
        void invalidNeverPasses() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(300_000).threads(1))
                    .consumers(c -> c.concurrency(4))
                    .runFor(Duration.ofMinutes(1))
                    .expect(Objective.throughputAtLeast(1_000))
                    .build();

            WorkloadReport report = report(workload, 90_000, 90_000,
                    constant(Duration.ofMillis(1).toNanos(), 1000),
                    constant(Duration.ofSeconds(4).toNanos(), 1000),
                    0, Duration.ofMinutes(1));

            // The throughput objective is comfortably met, and the run still cannot pass:
            // it did not measure what it was asked to.
            assertThat(report.consumeRate()).isGreaterThan(1_000);
            assertThat(report.passed()).isFalse();
            assertThat(report.isValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("a throughput objective reports the shortfall as a percentage")
        void throughputMissed() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(300_000).threads(8))
                    .consumers(c -> c.concurrency(8))
                    .runFor(Duration.ofMinutes(1))
                    .expect(Objective.throughputAtLeast(300_000))
                    .build();

            WorkloadReport report = report(workload, 12_000_000, 10_800_000,
                    constant(Duration.ofMillis(5).toNanos(), 1000),
                    constant(1000, 1000),
                    0, Duration.ofMinutes(1));

            assertThat(report.isValid()).isTrue();
            assertThat(report.passed()).isFalse();
            assertThat(report.problems())
                    .anySatisfy(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.FAILED);
                        assertThat(f.implication()).contains("60%");
                    });
        }

        @Test
        @DisplayName("a met throughput objective passes")
        void throughputMet() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(50_000).threads(4))
                    .consumers(c -> c.concurrency(8))
                    .runFor(Duration.ofMinutes(1))
                    .expect(Objective.throughputAtLeast(45_000))
                    .build();

            WorkloadReport report = report(workload, 3_000_000, 2_900_000,
                    constant(Duration.ofMillis(2).toNanos(), 1000),
                    constant(1000, 1000),
                    0, Duration.ofMinutes(1));

            assertThat(report.passed()).isTrue();
        }

        @Test
        @DisplayName("a latency objective is judged on the percentile, not the mean")
        void latencyObjective() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(1_000).threads(1))
                    .consumers(c -> c.concurrency(2))
                    .runFor(Duration.ofMinutes(1))
                    .expect(Objective.p99Below(Duration.ofMillis(50)))
                    .build();

            // 980 fast, 20 very slow. The mean is about 5ms and would pass a 50ms budget;
            // the p99 is 200ms and does not. A tool reporting the mean would call this healthy.
            //
            // Note the 2% tail rather than 1%: with exactly 10 slow samples in 1000 they are
            // the top 1% and p99 sits on the boundary, returning the fast value. The tail has
            // to be wider than the percentile being asked about.
            LatencyRecorder recorder = new LatencyRecorder("e2e");
            for (int i = 0; i < 980; i++) {
                recorder.record(Duration.ofMillis(1).toNanos());
            }
            for (int i = 0; i < 20; i++) {
                recorder.record(Duration.ofMillis(200).toNanos());
            }
            LatencySummary summary = recorder.summary();

            assertThat(summary.mean()).isLessThan(Duration.ofMillis(10));

            WorkloadReport report = report(workload, 60_000, 60_000, summary,
                    constant(1000, 1000), 0, Duration.ofMinutes(1));

            assertThat(report.passed()).isFalse();
            assertThat(report.problems())
                    .anySatisfy(f -> assertThat(f.observation()).contains("p99"));
        }
    }

    @Nested
    @DisplayName("diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("a growing queue is reported as a caveat on the latency, not a failure")
        void consumersBehind() {
            WorkloadReport report = report(workload(1_000, 1), 100_000, 40_000,
                    constant(Duration.ofSeconds(3).toNanos(), 1000),
                    constant(1000, 1000), 0, Duration.ofMinutes(1));

            assertThat(report.isValid()).isTrue();
            assertThat(report.findings())
                    .anySatisfy(f -> {
                        assertThat(f.rule()).isEqualTo("consumers-kept-up");
                        assertThat(f.severity()).isEqualTo(Severity.WARNING);
                        assertThat(f.implication()).contains("run's length");
                    });
        }

        @Test
        @DisplayName("publishing without confirms is flagged as an upper bound")
        void noConfirms() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(1_000).confirms(false))
                    .consumers(c -> c.concurrency(2))
                    .runFor(Duration.ofMinutes(1))
                    .build();

            WorkloadReport report = report(workload, 60_000, 60_000,
                    constant(Duration.ofMillis(1).toNanos(), 1000),
                    constant(1000, 1000), 0, Duration.ofMinutes(1));

            assertThat(report.findings())
                    .anySatisfy(f -> assertThat(f.implication()).contains("upper bound"));
        }

        @Test
        @DisplayName("a long tail is called out, because a median hides it")
        void longTail() {
            // 2% slow, for the same reason as above: a 1% tail lands exactly on p99.
            LatencyRecorder recorder = new LatencyRecorder("e2e");
            for (int i = 0; i < 9_800; i++) {
                recorder.record(Duration.ofMillis(1).toNanos());
            }
            for (int i = 0; i < 200; i++) {
                recorder.record(Duration.ofMillis(500).toNanos());
            }

            WorkloadReport report = report(workload(1_000, 1), 60_000, 60_000,
                    recorder.summary(), constant(1000, 1000), 0, Duration.ofMinutes(1));

            assertThat(report.findings())
                    .anySatisfy(f -> assertThat(f.rule()).isEqualTo("tail-is-not-extreme"));
        }

        @Test
        @DisplayName("a short run is flagged")
        void shortRun() {
            WorkloadReport report = report(workload(1_000, 1), 5_000, 5_000,
                    constant(Duration.ofMillis(1).toNanos(), 100),
                    constant(1000, 100), 0, Duration.ofSeconds(5));

            assertThat(report.findings())
                    .anySatisfy(f -> assertThat(f.rule()).isEqualTo("run-was-long-enough"));
        }

        @Test
        @DisplayName("findings are ordered worst first")
        void worstFirst() {
            Workload workload = Workload.named("test")
                    .publishers(p -> p.rate(300_000).threads(1).confirms(false))
                    .consumers(c -> c.concurrency(2))
                    .runFor(Duration.ofSeconds(5))
                    .expect(Objective.throughputAtLeast(300_000))
                    .build();

            WorkloadReport report = report(workload, 1_000, 1_000,
                    constant(Duration.ofMillis(1).toNanos(), 100),
                    constant(Duration.ofSeconds(4).toNanos(), 100), 0, Duration.ofSeconds(5));

            // Whoever prints the first line must see "this did not measure anything", not
            // "confirms were off".
            assertThat(report.findings().get(0).severity()).isEqualTo(Severity.INVALID);
        }
    }

    @Nested
    @DisplayName("the report")
    class Report {

        @Test
        @DisplayName("says INVALID rather than FAILED when the run did not measure")
        void formatsInvalid() {
            WorkloadReport report = report(workload(300_000, 1), 90_000, 90_000,
                    constant(Duration.ofMillis(5).toNanos(), 1000),
                    constant(Duration.ofSeconds(4).toNanos(), 1000), 0, Duration.ofMinutes(1));

            assertThat(report.format())
                    .contains("INVALID")
                    .doesNotContain("result: FAILED");
        }

        @Test
        @DisplayName("prints the offered rate beside the achieved one")
        void formatsRates() {
            WorkloadReport report = report(workload(1_000, 1), 60_000, 60_000,
                    constant(Duration.ofMillis(1).toNanos(), 1000),
                    constant(1000, 1000), 0, Duration.ofMinutes(1));

            assertThat(report.format())
                    .contains("offered 1,000/s")
                    .contains("published");
        }
    }
}
