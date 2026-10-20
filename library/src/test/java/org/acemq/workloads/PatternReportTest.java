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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.acemq.amqp.api.RetryPolicy;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.report.Reports;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Threshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a pattern's counts in the report")
class PatternReportTest {

    private static WorkloadReport report(Workload workload, Map<String, Long> patterns) {
        return new WorkloadReport(workload, Instant.now(), Duration.ofSeconds(10), 100, 100, 0, 100,
                LatencySummary.empty("e2e"), LatencySummary.empty("publish"),
                LatencySummary.empty("lag"), null, 0, null, List.of(), patterns);
    }

    private static Workload retrying() {
        return Workload.named("ladder")
                .publishers(p -> p.rate(1_000))
                .consumers(c -> c.failureRate(0.2).retry(RetryPolicy.fixed(3, Duration.ofMillis(100))))
                .build();
    }

    @Test
    @DisplayName("is printed, put in the JSON, and can carry a rule")
    void countsReachEveryFormat() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("retried", 40L);
        counts.put("recovered", 35L);
        counts.put("deadLettered", 5L);
        WorkloadReport report = report(retrying(), counts);

        assertThat(report.format()).contains("  deadLettered  5\n").contains("  retried       40\n");
        assertThat(Reports.toJson(List.of(report)))
                .contains("\"patterns\": {\"retried\": 40, \"recovered\": 35, \"deadLettered\": 5}");

        Rule rule = Threshold.named("few-dead").on("deadLettered").above(2).because("x");
        assertThat(rule.check(report).orElseThrow().observation())
                .isEqualTo("deadLettered was 5, above 2");
    }

    @Test
    @DisplayName("of a pattern the run did not use, fires no rule and prints nothing")
    void absentPattern() {
        WorkloadReport plain = report(retrying(), Map.of());

        assertThat(Threshold.named("x").on("outboxLeft").above(0).because("x").check(plain))
                .isEqualTo(Optional.empty());
        assertThat(Reports.toJson(List.of(plain))).doesNotContain("\"patterns\"");
        assertThat(plain.format()).doesNotContain("outbox");
    }

    @Test
    @DisplayName("survives a ramp: each step keeps the workload's patterns")
    void rampStepKeepsPatterns() {
        Workload base = Workload.named("relay")
                .publishers(p -> p.rate(100).outbox(25, Duration.ofMillis(10)))
                .consumers(c -> c.retry(RetryPolicy.fixed(2, Duration.ofMillis(50))))
                .build();

        Workload step = base.step(400, Duration.ofSeconds(1), Duration.ofSeconds(5), r -> Optional.empty());

        assertThat(step.publishers().rate()).isEqualTo(400);
        assertThat(step.publishers().outboxBatchSize()).isEqualTo(25);
        assertThat(step.publishers().outboxPollInterval()).isEqualTo(Duration.ofMillis(10));
        assertThat(step.consumers().retry()).isSameAs(base.consumers().retry());
    }

    @Test
    @DisplayName("the builder refuses an outbox it cannot measure")
    void outboxRefusals() {
        assertThatThrownBy(() -> Workload.named("x")
                .publishers(p -> p.outbox(10, Duration.ofMillis(10))).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a rate");
        assertThatThrownBy(() -> Workload.named("x")
                .publishers(p -> p.rate(10).outbox(10, Duration.ofMillis(10)).claimCheck(0, null)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be combined");
    }
}
