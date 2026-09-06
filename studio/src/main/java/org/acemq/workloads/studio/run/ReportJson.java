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
import java.util.List;

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.scenario.ScenarioReport;

/**
 * A report as JSON.
 *
 * <p>Written out by hand rather than serialised from the report object, so the shape the browser
 * and any pipeline reading it depend on is decided here and not by whatever fields the report
 * happens to have this month.
 *
 * @param scenario what was run
 * @param startedAt when
 * @param durationMs how long the measured window was
 * @param stoppedEarly whether somebody ended it
 * @param verdict passed, failed or invalid
 * @param valid whether the run measured what it claimed to
 * @param totalPublished everything published
 * @param totalConsumed everything consumed
 * @param blockedMs how long the broker refused publishes
 * @param blockedReason why, when it did
 * @param producers what each producer managed
 * @param queues what each queue saw
 * @param findings what the rules noticed
 */
public record ReportJson(
        String scenario,
        String startedAt,
        long durationMs,
        boolean stoppedEarly,
        String verdict,
        boolean valid,
        long totalPublished,
        long totalConsumed,
        long blockedMs,
        String blockedReason,
        List<ProducerJson> producers,
        List<QueueJson> queues,
        List<FindingJson> findings) {

    /**
     * @param name the producer
     * @param offeredRate what it was asked for
     * @param achievedRate what it managed
     * @param published how many
     * @param confirmed how many the broker took
     * @param failed how many it could not send
     * @param publishLatency send to confirm
     * @param sendLag how far behind its schedule it ran
     */
    public record ProducerJson(String name, long offeredRate, double achievedRate, long published,
            long confirmed, long failed, LatencyJson publishLatency, LatencyJson sendLag) {
    }

    /**
     * @param name the queue
     * @param type what kind
     * @param consumers how many were on it
     * @param consumed how many they handled
     * @param consumeRate per second
     * @param endToEnd due to handled
     * @param depthAtStart what was waiting when measurement began
     * @param depthAtEnd what was waiting at the end
     * @param grew whether the backlog was still growing
     */
    public record QueueJson(String name, String type, int consumers, long consumed,
            double consumeRate, LatencyJson endToEnd, Long depthAtStart, Long depthAtEnd,
            boolean grew) {
    }

    /**
     * The percentiles the recorder actually keeps.
     *
     * <p>p90 rather than p95, because the summary retains 50, 90, 99, 99.9 and 99.99 and refuses
     * to be asked for anything else. That refusal is the right one -- a percentile cannot be
     * interpolated out of the ones either side of it, and a p95 invented here would be a number
     * on a screen that nothing measured.
     *
     * @param count how many measurements
     * @param p50 the median, in milliseconds
     * @param p90 the 90th percentile
     * @param p99 the 99th
     * @param p999 the 99.9th, which is where a tail shows itself
     * @param max the worst
     */
    public record LatencyJson(long count, double p50, double p90, double p99, double p999,
            double max) {

        static LatencyJson of(LatencySummary summary) {
            if (summary == null || summary.count() == 0) {
                return new LatencyJson(0, 0, 0, 0, 0, 0);
            }
            return new LatencyJson(summary.count(),
                    millis(summary.p50()),
                    millis(summary.p90()),
                    millis(summary.p99()),
                    millis(summary.p999()),
                    millis(summary.max()));
        }

        private static double millis(java.time.Duration duration) {
            return duration.toNanos() / 1_000_000.0;
        }
    }

    /**
     * @param rule which rule
     * @param severity how much it matters
     * @param observation what was measured
     * @param implication what that rules in or out -- never what to do about it
     */
    public record FindingJson(String rule, String severity, String observation,
            String implication) {
    }

    /**
     * @param report a report
     * @return the same thing as JSON
     */
    public static ReportJson of(ScenarioReport report) {
        List<ProducerJson> producers = new ArrayList<>();
        for (ScenarioReport.ProducerResult producer : report.producers()) {
            producers.add(new ProducerJson(producer.name(), producer.offeredRate(),
                    producer.achievedRate(report.duration()), producer.published(),
                    producer.confirmed(), producer.failed(),
                    LatencyJson.of(producer.publishLatency()),
                    LatencyJson.of(producer.sendLag())));
        }

        List<QueueJson> queues = new ArrayList<>();
        for (ScenarioReport.QueueResult queue : report.queues()) {
            queues.add(new QueueJson(queue.name(), queue.type().wireName(), queue.consumers(),
                    queue.consumed(), queue.consumeRate(report.duration()),
                    LatencyJson.of(queue.endToEnd()), queue.depthAtStart(), queue.depthAtEnd(),
                    queue.grew()));
        }

        List<FindingJson> findings = new ArrayList<>();
        for (Finding finding : report.findings()) {
            findings.add(new FindingJson(finding.rule(), finding.severity().name(),
                    finding.observation(), finding.implication()));
        }

        return new ReportJson(
                report.scenario().name(),
                report.startedAt().toString(),
                report.duration().toMillis(),
                report.wasStoppedEarly(),
                !report.isValid() ? "invalid" : report.passed() ? "passed" : "failed",
                report.isValid(),
                report.totalPublished(),
                report.totalConsumed(),
                report.blockedFor().toMillis(),
                report.blockedReason(),
                producers, queues, findings);
    }
}
