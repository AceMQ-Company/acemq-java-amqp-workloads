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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.report.Reports;
import org.acemq.workloads.rules.Objective;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ramp's logic, against a simulated broker: each step's report is built from a capacity
 * rather than measured, so where the ramp stops is known exactly.
 */
@DisplayName("a ramp")
class RampTest {

    private static final Duration STEP = Duration.ofSeconds(10);

    private static Workload workload() {
        return Workload.named("ramp")
                .publishers(p -> p.rate(1_000).threads(2))
                .consumers(c -> c.concurrency(2))
                .warmup(Duration.ofSeconds(1))
                .runFor(STEP)
                .build();
    }

    /**
     * A broker that consumes at most {@code capacity} a second. Its generator falls behind above
     * {@code generatorLimit}, and a share of publishes fail above {@code failingAbove}.
     */
    private static Function<Workload, WorkloadReport> broker(long capacity, long generatorLimit,
            long failingAbove, double failShare) {
        return step -> {
            long rate = step.publishers().rate();
            long consumedRate = Math.min(rate, capacity);
            long seconds = STEP.toSeconds();
            long published = rate * seconds;
            long failed = rate > failingAbove ? Math.round(published * failShare) : 0;
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i <= seconds; i++) {
                samples.add(new Sample(Instant.now(), Duration.ofSeconds(i), Sample.Phase.MEASURING,
                        rate * i, rate * i, 0, 0, consumedRate * i,
                        // The first reading straddles the reset and reads zero, as the engine's does.
                        i == 0 ? 0 : rate, i == 0 ? 0 : consumedRate,
                        LatencySummary.empty("e2e"), LatencySummary.empty("lag"), null, false));
            }
            long lagNanos = rate > generatorLimit ? 500_000_000L : 1_000_000L;
            return new WorkloadReport(step, Instant.now(), STEP, published, published, failed,
                    // The drain lets a slow consumer catch up on the totals, which is why the
                    // pace check reads the samples instead.
                    published,
                    constant(2_000_000L + (rate > capacity ? 900_000_000L : 0)),
                    LatencySummary.empty("publish"), constant(lagNanos), 0L, 0, null, samples);
        };
    }

    private static Function<Workload, WorkloadReport> broker(long capacity) {
        return broker(capacity, Long.MAX_VALUE, Long.MAX_VALUE, 0);
    }

    private static LatencySummary constant(long nanos) {
        LatencyRecorder recorder = new LatencyRecorder("test");
        for (int i = 0; i < 2_000; i++) {
            recorder.record(nanos);
        }
        return recorder.summary();
    }

    private static List<Long> rates(RampReport report) {
        return report.steps().stream().map(RampReport.Step::rate).toList();
    }

    @Test
    @DisplayName("climbs by a fixed step and stops at the first rate the consumers cannot match")
    void stopsAtCapacity() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000)
                .run(broker(3_500));

        assertThat(rates(report)).containsExactly(1_000L, 2_000L, 3_000L, 4_000L);
        assertThat(report.highestSustainedRate()).isEqualTo(3_000);
        assertThat(report.stoppedBy()).isEqualTo("kept-pace");
        assertThat(report.stoppingStep()).get().extracting(RampReport.Step::rate).isEqualTo(4_000L);
        assertThat(report.isValid()).isTrue();
        assertThat(report.passed()).isTrue();
        assertThat(report.format()).contains("highest sustained rate: 3,000/s")
                .contains("kept-pace at 4,000/s");
    }

    @Test
    @DisplayName("the delivered rate may fall short by the tolerance and still hold")
    void tolerance() {
        // 4,000 offered against 3,900 delivered is 97.5%: inside 5%, outside 1%.
        assertThat(Ramp.of(workload()).startAt(4_000).stepBy(1_000).maxRate(4_000)
                .run(broker(3_900)).highestSustainedRate()).isEqualTo(4_000);
        assertThat(Ramp.of(workload()).startAt(4_000).stepBy(1_000).maxRate(4_000).tolerance(0.01)
                .run(broker(3_900)).highestSustainedRate()).isZero();
    }

    @Test
    @DisplayName("a percentage step grows with the rate")
    void percentStep() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepByPercent(50)
                .run(broker(3_000));

        assertThat(rates(report)).containsExactly(1_000L, 1_500L, 2_250L, 3_375L);
        assertThat(report.highestSustainedRate()).isEqualTo(2_250);
    }

    @Test
    @DisplayName("refinement bisects between the last good and the first bad rate")
    void refines() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000).tolerance(0)
                .refine(3).run(broker(1_370));

        assertThat(rates(report)).containsExactly(1_000L, 2_000L, 1_500L, 1_250L, 1_375L);
        assertThat(report.steps().subList(2, 5)).allMatch(RampReport.Step::refinement);
        assertThat(report.highestSustainedRate()).isEqualTo(1_250);
        // What stopped the climb is still the climb's first bad step.
        assertThat(report.stoppingStep()).get().extracting(RampReport.Step::rate).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("reaching maxRate with every rule holding says 'at least', and stops there")
    void maxRate() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000).maxRate(2_500)
                .run(broker(1_000_000));

        assertThat(rates(report)).containsExactly(1_000L, 2_000L, 2_500L);
        assertThat(report.reachedMaxRate()).isTrue();
        assertThat(report.highestSustainedRate()).isEqualTo(2_500);
        assertThat(report.stopReason()).contains("at least");
        assertThat(report.passed()).isTrue();
    }

    @Test
    @DisplayName("a generator that falls behind ends the ramp as invalid, not as the broker's ceiling")
    void generatorLimit() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000)
                .run(broker(1_000_000, 2_500, Long.MAX_VALUE, 0));

        assertThat(report.highestSustainedRate()).isEqualTo(2_000);
        assertThat(report.stoppedBy()).contains("generator-kept-up");
        assertThat(report.isValid()).isFalse();
        assertThat(report.verdict()).isEqualTo("INVALID");
        assertThat(report.format()).contains("not the broker's ceiling");
    }

    @Test
    @DisplayName("ceilingAtLeast decides the verdict: met is a pass however the climb ended")
    void ceilingAtLeast() {
        Function<Workload, WorkloadReport> broker = broker(1_000_000, 2_500, Long.MAX_VALUE, 0);
        assertThat(Ramp.of(workload()).startAt(1_000).stepBy(1_000).ceilingAtLeast(2_000)
                .run(broker).passed()).isTrue();
        RampReport missed = Ramp.of(workload()).startAt(1_000).stepBy(1_000).ceilingAtLeast(5_000)
                .run(broker(3_500));
        assertThat(missed.passed()).isFalse();
        assertThat(missed.verdict()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("failed publishes stop it, unless they are within maxErrorRate")
    void errorRate() {
        Function<Workload, WorkloadReport> broker = broker(1_000_000, Long.MAX_VALUE, 1_500, 0.005);

        RampReport strict = Ramp.of(workload()).startAt(1_000).stepBy(1_000).maxRate(3_000)
                .run(broker);
        assertThat(strict.highestSustainedRate()).isEqualTo(1_000);
        assertThat(strict.stoppedBy()).isEqualTo("publishes-succeeded");

        RampReport lenient = Ramp.of(workload()).startAt(1_000).stepBy(1_000).maxRate(3_000)
                .maxErrorRate(0.01).run(broker);
        assertThat(lenient.highestSustainedRate()).isEqualTo(3_000);

        RampReport tight = Ramp.of(workload()).startAt(1_000).stepBy(1_000).maxRate(3_000)
                .maxErrorRate(0.001).run(broker);
        assertThat(tight.stoppedBy()).isEqualTo("maxErrorRate");
    }

    @Test
    @DisplayName("an objective on the workload stops the ramp when it is missed")
    void objectiveStops() {
        Workload withObjective = Workload.named("ramp")
                .publishers(p -> p.rate(1_000))
                .runFor(STEP)
                .expect(Objective.p99Below(Duration.ofMillis(100)))
                .build();
        RampReport report = Ramp.of(withObjective).startAt(1_000).stepBy(1_000).tolerance(0.5)
                .run(broker(2_500));

        // 3,000 is inside the 50% tolerance, so it is the latency objective that stops it.
        assertThat(report.highestSustainedRate()).isEqualTo(2_000);
        assertThat(report.stoppedBy()).isEqualTo("p99<100.0ms");
    }

    @Test
    @DisplayName("a start rate that already misses is a failure with no ceiling")
    void firstStepFails() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000).refine(3)
                .run(broker(100));

        assertThat(report.steps()).hasSize(1);
        assertThat(report.highestSustainedRate()).isZero();
        assertThat(report.passed()).isFalse();
        assertThat(report.verdict()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("refuses settings that cannot make a ramp")
    void refusals() {
        assertThatThrownBy(() -> Ramp.of(Workload.named("u").publishers(p -> p.unthrottled())
                .build()).stepBy(10).check()).hasMessageContaining("needs a starting rate");
        assertThatThrownBy(() -> Ramp.of(workload()).startAt(1_000).check())
                .hasMessageContaining("needs a step");
        assertThatThrownBy(() -> Ramp.of(workload()).stepBy(10).stepFor(Duration.ofSeconds(2)))
                .hasMessageContaining("at least 5s");
        assertThatThrownBy(() -> Ramp.of(workload()).startAt(5_000).stepBy(10).maxRate(1_000)
                .check()).hasMessageContaining("above its maxRate");
    }

    @Test
    @DisplayName("every report format carries the steps and the curve")
    void reports() {
        RampReport report = Ramp.of(workload()).startAt(1_000).stepBy(1_000)
                .run(broker(3_500));

        String json = Reports.toJson(List.of(), List.of(report));
        assertThat(json).contains("\"ramps\"").contains("\"highestSustainedRatePerSecond\": 3000")
                .contains("\"stoppedBy\": \"kept-pace\"").contains("\"p99Micros\"");
        assertThat(Reports.toMarkdown(List.of(), List.of(report)))
                .contains("## Ramp: ramp").contains("| 4,000/s | FAILED |")
                .contains("p99 against offered rate");
        assertThat(Reports.toHtml(List.of(), List.of(report)))
                .contains("<svg").contains("<polyline").contains("circle cx");
        // Existing callers, with no ramps, see exactly the old shape.
        assertThat(Reports.toJson(List.of())).doesNotContain("ramps");
    }
}
