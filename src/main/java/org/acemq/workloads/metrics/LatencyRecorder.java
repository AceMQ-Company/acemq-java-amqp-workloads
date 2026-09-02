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
package org.acemq.workloads.metrics;

import java.util.concurrent.TimeUnit;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * Records latencies, from many threads, without distorting them.
 *
 * <h2>Why a histogram and not a list</h2>
 *
 * <p>Two reasons, and both are fatal to the alternative.
 *
 * <p><strong>Percentiles cannot be averaged.</strong> The mean of each thread's p99 is not the
 * p99, and neither is the mean of each second's p99. Any design that summarises per thread or
 * per interval and combines the summaries afterwards produces a number that is not a percentile
 * of anything. The samples have to meet in one place.
 *
 * <p><strong>A list does not fit.</strong> At 300,000 messages a second for five minutes that is
 * ninety million longs — 720MB, allocated during the measurement, which the garbage collector
 * then pays for in exactly the pauses being measured.
 *
 * <p>{@link ConcurrentHistogram} solves both: fixed memory, lock-free recording, and exact
 * percentiles to the configured precision.
 */
public final class LatencyRecorder {

    /** One hour. A latency above this is a hang, not a measurement. */
    private static final long MAX_TRACKABLE_NANOS = TimeUnit.HOURS.toNanos(1);

    private final Histogram histogram;
    private final String name;

    /**
     * @param name what is being measured, for the report
     */
    public LatencyRecorder(String name) {
        this.name = name;
        // Three significant digits: 1.00ms and 1.01ms are distinguished, which is more
        // resolution than any conclusion here needs and keeps the footprint small.
        this.histogram = new ConcurrentHistogram(MAX_TRACKABLE_NANOS, 3);
    }

    /**
     * @param nanos one observation
     */
    public void record(long nanos) {
        // A negative value means the clocks disagree or the message predates the run; recording
        // it would throw and abort a run over one sample.
        histogram.recordValue(Math.max(0, Math.min(nanos, MAX_TRACKABLE_NANOS)));
    }

    /** @return a snapshot of everything recorded so far */
    public LatencySummary summary() {
        return LatencySummary.of(name, histogram.copy());
    }

    /** @return how many observations have been recorded */
    public long count() {
        return histogram.getTotalCount();
    }

    /** Discards everything recorded so far, which is how warm-up is excluded. */
    public void reset() {
        histogram.reset();
    }

    @Override
    public String toString() {
        return "LatencyRecorder{" + name + ", n=" + count() + "}";
    }
}
