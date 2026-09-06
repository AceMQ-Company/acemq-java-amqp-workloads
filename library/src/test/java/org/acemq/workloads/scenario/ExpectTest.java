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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Severity;
import org.junit.jupiter.api.Test;

/**
 * What a scenario is asked to prove, and what happens when it does not.
 *
 * <p>These are the tests behind the exit code. A scenario that cannot fail on a number is a
 * description, and a pipeline cannot act on a description; what is checked here is that an
 * expectation survives the file, reaches the report, and turns into a {@link Severity#FAILED}
 * finding that names the node and the measurement.
 */
class ExpectTest {

    @Test
    void anExpectationSurvivesTheFile() throws Exception {
        Scenario scenario = Scenario.named("gated")
                .exchange("orders", "topic")
                .queue("orders.shipping", q -> q.quorum().boundTo("orders", "#")
                        .expect(e -> e.p99Below(Duration.ofMillis(50)).noBacklog(true)))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(5_000)
                        .expect(e -> e.withinPercentOfOffered(5).noFailures(true)));

        ObjectMapper json = new ObjectMapper();
        String written = json.writeValueAsString(ScenarioFile.of(scenario, null, null, null));
        Scenario read = json.readValue(written, ScenarioFile.class).toScenario();

        Expect queue = read.findQueue("orders.shipping").orElseThrow().expectations();
        assertThat(queue.p99BelowValue()).isEqualTo(Duration.ofMillis(50));
        assertThat(queue.requiresNoBacklog()).isTrue();

        Expect producer = read.producers().get(0).expectations();
        assertThat(producer.withinPercentOfOfferedValue()).isEqualTo(5);
        assertThat(producer.requiresNoFailures()).isTrue();

        // Nothing asked for is nothing written: a file full of nulls is a file nobody edits.
        assertThat(written).doesNotContain("\"p50Below\"").doesNotContain("\"consumeRateAtLeast\"");
    }

    @Test
    void aQueueSlowerThanItWasAskedForFailsTheRun() {
        ScenarioReport report = reportWhere(
                queueResult("orders.shipping", latencyOf(Duration.ofMillis(120)), 10_000, 0, 0,
                        new Expect().p99Below(Duration.ofMillis(50))),
                producerResult("checkout", 10_000, 10_000, 0, null));

        assertThat(report.passed()).isFalse();
        assertThat(failures(report))
                .anyMatch(message -> message.contains("orders.shipping p99")
                        && message.contains("under 50ms"));
    }

    @Test
    void aQueueInsideItsLimitDoesNot() {
        ScenarioReport report = reportWhere(
                queueResult("orders.shipping", latencyOf(Duration.ofMillis(4)), 10_000, 0, 0,
                        new Expect().p99Below(Duration.ofMillis(50)).noBacklog(true)),
                producerResult("checkout", 10_000, 10_000, 0, new Expect().noFailures(true)));

        assertThat(failures(report)).isEmpty();
        assertThat(report.passed()).isTrue();
    }

    // The failure worth being loudest about: an expectation about a queue that received nothing
    // is unanswerable, and unanswerable is not the same as met.
    @Test
    void aQueueThatSawNothingCannotSatisfyALatencyExpectation() {
        ScenarioReport report = reportWhere(
                queueResult("orders.shipping", LatencySummary.empty("end-to-end"), 0, 0, 0,
                        new Expect().p99Below(Duration.ofMillis(50))),
                producerResult("checkout", 10_000, 10_000, 0, null));

        assertThat(failures(report))
                .anyMatch(message -> message.contains("orders.shipping handled nothing"));
    }

    @Test
    void aGeneratorThatFellShortOfItsOwnRateFailsTheRun() {
        // 6,000 published over 10 seconds is 600/s against 10,000/s asked for.
        ScenarioReport report = reportWhere(
                queueResult("orders.shipping", latencyOf(Duration.ofMillis(2)), 6_000, 0, 0, null),
                producerResult("checkout", 10_000, 6_000, 0, new Expect().withinPercentOfOffered(5)));

        assertThat(failures(report))
                .anyMatch(message -> message.contains("checkout offered")
                        && message.contains("5% allowed"));
    }

    @Test
    void aStreamIsNeverAccusedOfABacklog() {
        // Every message read and every message retained: a log, not a queue that fell behind.
        ScenarioReport report = reportWhere(
                new ScenarioReport.QueueResult("events", QueueType.STREAM, 2, 10_000,
                        latencyOf(Duration.ofMillis(2)), 0L, 10_000L,
                        new Expect().noBacklog(true)),
                producerResult("checkout", 1_000, 10_000, 0, null));

        assertThat(failures(report)).isEmpty();
    }

    @Test
    void aFailedPublishFailsAProducerAskedForNone() {
        ScenarioReport report = reportWhere(
                queueResult("orders.shipping", latencyOf(Duration.ofMillis(2)), 9_997, 0, 0, null),
                producerResult("checkout", 1_000, 9_997, 3, new Expect().noFailures(true)));

        assertThat(failures(report))
                .anyMatch(message -> message.contains("checkout had 3 publishes fail"));
    }

    @Test
    void whatIsNotStatedIsNotChecked() {
        assertThat(new Expect().isEmpty()).isTrue();
        assertThat(new Expect().toString()).isEqualTo("nothing in particular");
        assertThat(new Expect().noFailures(false).isEmpty()).isTrue();
        assertThat(new Expect().p50Below(Duration.ofMillis(1)).isEmpty()).isFalse();
    }

    private static List<String> failures(ScenarioReport report) {
        return report.findings().stream()
                .filter(finding -> finding.severity() == Severity.FAILED)
                .map(Finding::observation)
                .toList();
    }

    private static ScenarioReport.QueueResult queueResult(String name, LatencySummary latency,
            long consumed, long depthAtStart, long depthAtEnd, Expect expect) {
        return new ScenarioReport.QueueResult(name, QueueType.QUORUM, 4, consumed, latency,
                depthAtStart, depthAtEnd, expect);
    }

    private static ScenarioReport.ProducerResult producerResult(String name, long offeredRate,
            long published, long failed, Expect expect) {
        return new ScenarioReport.ProducerResult(name, offeredRate, published, published - failed,
                failed, latencyOf(Duration.ofMillis(1)), latencyOf(Duration.ofMillis(1)), expect);
    }

    /**
     * @param at every recorded value, so the percentiles are all this
     * @return a summary of a run that was uniformly this fast
     */
    private static LatencySummary latencyOf(Duration at) {
        LatencyRecorder recorder = new LatencyRecorder("end-to-end");
        for (int i = 0; i < 1_000; i++) {
            recorder.record(at.toNanos());
        }
        return recorder.summary();
    }

    private static ScenarioReport reportWhere(ScenarioReport.QueueResult queue,
            ScenarioReport.ProducerResult producer) {
        Scenario scenario = Scenario.named("gated")
                .exchange("orders", "topic")
                .queue(queue.name(), q -> q.boundTo("orders", "#"))
                .producer(producer.name(), p -> p.to("orders", "k"));

        return new ScenarioReport(scenario, Instant.now(), Duration.ofSeconds(10),
                List.of(producer), List.of(queue), 0, null, false);
    }
}
