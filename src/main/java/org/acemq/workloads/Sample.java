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

import org.acemq.workloads.metrics.LatencySummary;

/**
 * One reading taken while a workload is running.
 *
 * <p>A run takes minutes and reports at the end, which is the right shape for a pipeline and the
 * wrong one for a person watching. These are what a live view draws, and they are readings rather
 * than results: the rates are per-interval, so a stall shows as a trough here where the final
 * report can only show a lower average.
 *
 * <p>The latency summaries are cumulative for the phase, not for the interval. HdrHistogram
 * cannot subtract, and an interval percentile computed by resetting the recorder would corrupt
 * the numbers the report is built from — so a live p99 that only rises is the honest one to show.
 *
 * @param at when this reading was taken
 * @param elapsed how long the run has been going, including warm-up
 * @param phase what the run was doing
 * @param published messages published so far in this phase
 * @param confirmed messages the broker has confirmed
 * @param failed publishes refused or errored
 * @param consumed messages the consumers have handled
 * @param publishRate publishes per second since the previous reading
 * @param consumeRate consumes per second since the previous reading
 * @param endToEnd latency from when a message was due to when it was handled, cumulative
 * @param sendLag how far behind their own schedule publishes are going out, cumulative
 * @param queueDepth messages waiting, when the broker will say
 * @param blocked whether the broker is refusing publishes for a resource alarm
 */
public record Sample(
        Instant at,
        Duration elapsed,
        Phase phase,
        long published,
        long confirmed,
        long failed,
        long consumed,
        double publishRate,
        double consumeRate,
        LatencySummary endToEnd,
        LatencySummary sendLag,
        Long queueDepth,
        boolean blocked) {

    /** What a run is doing when a reading is taken. */
    public enum Phase {

        /** Declaring the topology and starting publishers and consumers. */
        STARTING,

        /**
         * Running, with the numbers thrown away.
         *
         * <p>Worth showing rather than hiding: a warm-up that looks nothing like the measured
         * phase is itself information, and somebody watching a blank screen for ten seconds
         * assumes the tool is broken.
         */
        WARMUP,

        /** Running, and counting. */
        MEASURING,

        /** Time is up; waiting for what is in flight to land. */
        DRAINING
    }

    @Override
    public String toString() {
        return "Sample{" + phase + " at " + elapsed.toSeconds() + "s, "
                + Math.round(publishRate) + "/s out, " + Math.round(consumeRate) + "/s in}";
    }
}
