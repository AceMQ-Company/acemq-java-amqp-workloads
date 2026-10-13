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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Severity;
import org.acemq.workloads.rules.Threshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a threshold rule")
class ThresholdTest {

    private static WorkloadReport report(long published, long consumed, long failed,
            long p99Nanos, Long depth) {
        LatencyRecorder recorder = new LatencyRecorder("e2e");
        for (int i = 0; i < 100; i++) {
            recorder.record(p99Nanos);
        }
        Workload workload = Workload.named("t").publishers(p -> p.rate(1_000)).build();
        return new WorkloadReport(workload, Instant.now(), Duration.ofSeconds(10),
                published, published, failed, consumed, recorder.summary(),
                LatencySummary.empty("publish"), LatencySummary.empty("lag"), depth, 0, null);
    }

    @Test
    @DisplayName("fires above its limit, with the number in the observation")
    void above() {
        Rule rule = Threshold.named("deep").on("queueDepthAtEnd").above(1_000)
                .because("the queue was filling");

        assertThat(rule.check(report(10, 10, 0, 1, 999L))).isEmpty();
        Finding finding = rule.check(report(10, 10, 0, 1, 5_000L)).orElseThrow();
        assertThat(finding.rule()).isEqualTo("deep");
        assertThat(finding.severity()).isEqualTo(Severity.FAILED);
        assertThat(finding.observation()).isEqualTo("queueDepthAtEnd was 5,000, above 1,000");
        assertThat(finding.implication()).isEqualTo("the queue was filling");
    }

    @Test
    @DisplayName("compares latencies as durations, and rates below a floor")
    void latencyAndBelow() {
        Rule tail = Threshold.named("tail").on("endToEnd.p99").above(Duration.ofMillis(50))
                .severity(Severity.WARNING).because(null);
        assertThat(tail.check(report(10, 10, 0, 40_000_000L, null))).isEmpty();
        Finding finding = tail.check(report(10, 10, 0, 80_000_000L, null)).orElseThrow();
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.observation()).startsWith("endToEnd.p99 was 80.").endsWith("above 50.0ms");

        Rule floor = Threshold.named("floor").on("consumeRate").below(500).because("too slow");
        assertThat(floor.check(report(10_000, 10_000, 0, 1, null))).isEmpty();
        assertThat(floor.check(report(10_000, 1_000, 0, 1, null))).isPresent();
    }

    @Test
    @DisplayName("a metric the run did not produce never fires")
    void missingMetric() {
        Rule rule = Threshold.named("deep").on("queueDepthAtEnd").above(0).because("x");
        assertThat(rule.check(report(10, 10, 0, 1, null))).isEmpty();
    }

    @Test
    @DisplayName("errorRate is a fraction of attempts")
    void errorRate() {
        Rule rule = Threshold.named("errors").on("errorRate").above(0.01).because("x");
        assertThat(rule.check(report(1_000, 1_000, 5, 1, null))).isEmpty();
        assertThat(rule.check(report(1_000, 1_000, 50, 1, null)).orElseThrow().observation())
                .contains("4.76%").contains("1.00%");
    }

    @Test
    @DisplayName("refuses an unknown metric, a duration on a count, and a rule with no limit")
    void refusals() {
        assertThatThrownBy(() -> Threshold.named("x").on("p99"))
                .hasMessageContaining("unknown metric 'p99'").hasMessageContaining("endToEnd.p99");
        assertThatThrownBy(() -> Threshold.named("x").on("published").above(Duration.ofSeconds(1)))
                .hasMessageContaining("latency metric");
        assertThatThrownBy(() -> Threshold.named("x").on("published").because("y"))
                .hasMessageContaining("'above' or 'below'");
    }
}
