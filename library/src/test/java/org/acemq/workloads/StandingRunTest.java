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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.report.Reports;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A run with no fixed end, and a report that says what happened while it ran.
 *
 * <p>Both exist for the same caller: a generator kept beside a cluster somebody else is
 * experimenting on. It has no natural length, and the aggregate it used to produce could not say
 * what clients saw at the moment the experiment did something.
 */
@DisplayName("a standing run")
class StandingRunTest {

    private static Workload workload() {
        return Workload.named("standing")
                .topology(t -> t.queue("q"))
                .runUntilStopped()
                .build();
    }

    @Test
    @DisplayName("ends only when it is stopped")
    void runsUntilStopped() {
        assertTrue(workload().runsUntilStopped());
    }

    @Test
    @DisplayName("is not what an ordinary run says about itself")
    void anOrdinaryRunIsNotStanding() {
        Workload fixed = Workload.named("fixed")
                .topology(t -> t.queue("q"))
                .runFor(Duration.ofSeconds(30))
                .build();
        assertFalse(fixed.runsUntilStopped());
    }

    /**
     * The sentinel has to be a duration the engine can add to a clock.
     *
     * <p>`Duration.ofNanos(Long.MAX_VALUE)` would have been the obvious way to write "forever" and
     * is the one that breaks: the engine computes its deadline as `System.nanoTime() + duration`,
     * which overflows, and the run would end immediately on a deadline already in the past.
     */
    @Test
    @DisplayName("has a length a clock can hold")
    void theSentinelDoesNotOverflow() {
        long deadline = System.nanoTime() + Workload.UNTIL_STOPPED.toNanos();
        assertTrue(deadline > System.nanoTime(),
                "the deadline for a standing run is in the past, so it would end at once");
    }

    /** A zero or negative window is still refused. "Until stopped" is asked for by name. */
    @Test
    @DisplayName("is not what an empty duration means")
    void zeroIsStillRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Workload.named("x").runFor(Duration.ZERO));
    }

    //// ----------------------------------------------------------------- the series

    private static Sample sample(long millis, long published, long consumed, Sample.Phase phase) {
        return new Sample(Instant.parse("2026-09-24T18:00:00Z").plusMillis(millis),
                Duration.ofMillis(millis), phase,
                published, published, 0, consumed,
                200.0, 199.5,
                LatencySummary.empty("end-to-end"), LatencySummary.empty("send lag"),
                7L, false);
    }

    private static WorkloadReport reportWith(List<Sample> samples) {
        return new WorkloadReport(workload(), Instant.now(), Duration.ofSeconds(3),
                600, 600, 0, 598,
                LatencySummary.empty("end-to-end"), LatencySummary.empty("publish"),
                LatencySummary.empty("send lag"),
                0L, 0, null, samples);
    }

    @Test
    @DisplayName("keeps what it saw as it went")
    void theReportKeepsItsSamples() {
        WorkloadReport report = reportWith(List.of(
                sample(1000, 200, 198, Sample.Phase.MEASURING),
                sample(2000, 400, 399, Sample.Phase.MEASURING)));

        assertEquals(2, report.samples().size());
        assertEquals(Duration.ofMillis(2000), report.samples().get(1).elapsed());
    }

    /**
     * The series is published, because a report nothing can read is not evidence.
     *
     * <p>Each entry carries the wall-clock instant as well as the elapsed time, so something that
     * was not this process — a fault drill with its own timeline — can line the two up.
     */
    @Test
    @DisplayName("publishes the series as JSON, with wall-clock times")
    void theSeriesIsPublished() {
        String json = Reports.toJson(List.of(reportWith(List.of(
                sample(1000, 200, 198, Sample.Phase.WARMUP),
                sample(2000, 400, 399, Sample.Phase.MEASURING)))));

        assertTrue(json.contains("\"samples\""), json);
        assertTrue(json.contains("\"elapsedMillis\": 1000"), json);
        assertTrue(json.contains("\"at\": \"2026-09-24T18:00:01Z\""), json);
        assertTrue(json.contains("\"phase\": \"WARMUP\""), json);
        assertTrue(json.contains("\"queueDepth\": 7"), json);
    }

    /**
     * A run shorter than one interval measured no intervals, and says so by omission.
     *
     * <p>`"samples": []` would invite the reader to conclude the run was idle, which is a different
     * statement from having nothing to report.
     */
    @Test
    @DisplayName("says nothing rather than saying empty")
    void noSamplesMeansNoKey() {
        String json = Reports.toJson(List.of(reportWith(List.of())));
        assertFalse(json.contains("\"samples\""), json);
    }

    /** The old constructor still works, and reports no series rather than null. */
    @Test
    @DisplayName("leaves a report built without samples usable")
    void samplesAreNeverNull() {
        WorkloadReport report = new WorkloadReport(workload(), Instant.now(), Duration.ofSeconds(1),
                1, 1, 0, 1,
                LatencySummary.empty("end-to-end"), LatencySummary.empty("publish"),
                LatencySummary.empty("send lag"), null, 0, null);
        assertTrue(report.samples().isEmpty());
    }
}
