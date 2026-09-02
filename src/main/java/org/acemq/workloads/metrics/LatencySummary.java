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

import java.time.Duration;

import org.HdrHistogram.Histogram;

/**
 * Latency percentiles from one run.
 *
 * <p>The percentiles are reported and the mean is available but deliberately not printed first.
 * A mean latency is the number least likely to describe anybody's experience: it hides the tail
 * entirely, and the tail is what users notice and what capacity planning is about.
 */
public final class LatencySummary {

    private final String name;
    private final long count;
    private final long minNanos;
    private final long maxNanos;
    private final double meanNanos;
    private final long p50;
    private final long p90;
    private final long p99;
    private final long p999;
    private final long p9999;

    private LatencySummary(String name, long count, long minNanos, long maxNanos, double meanNanos,
            long p50, long p90, long p99, long p999, long p9999) {
        this.name = name;
        this.count = count;
        this.minNanos = minNanos;
        this.maxNanos = maxNanos;
        this.meanNanos = meanNanos;
        this.p50 = p50;
        this.p90 = p90;
        this.p99 = p99;
        this.p999 = p999;
        this.p9999 = p9999;
    }

    static LatencySummary of(String name, Histogram histogram) {
        return new LatencySummary(name,
                histogram.getTotalCount(),
                histogram.getTotalCount() == 0 ? 0 : histogram.getMinValue(),
                histogram.getMaxValue(),
                histogram.getMean(),
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(90.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getValueAtPercentile(99.9),
                histogram.getValueAtPercentile(99.99));
    }

    /** @return an empty summary, for a measurement that recorded nothing */
    public static LatencySummary empty(String name) {
        return new LatencySummary(name, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public String name() {
        return name;
    }

    /** @return how many observations this is built from */
    public long count() {
        return count;
    }

    public Duration min() {
        return Duration.ofNanos(minNanos);
    }

    public Duration max() {
        return Duration.ofNanos(maxNanos);
    }

    public Duration mean() {
        return Duration.ofNanos((long) meanNanos);
    }

    public Duration p50() {
        return Duration.ofNanos(p50);
    }

    public Duration p90() {
        return Duration.ofNanos(p90);
    }

    public Duration p99() {
        return Duration.ofNanos(p99);
    }

    /** @return the 99.9th percentile: one request in a thousand is worse than this */
    public Duration p999() {
        return Duration.ofNanos(p999);
    }

    public Duration p9999() {
        return Duration.ofNanos(p9999);
    }

    /**
     * @param percentile between 0 and 100
     * @return the value at it
     */
    public Duration percentile(double percentile) {
        if (percentile == 50.0) {
            return p50();
        }
        if (percentile == 90.0) {
            return p90();
        }
        if (percentile == 99.0) {
            return p99();
        }
        if (percentile == 99.9) {
            return p999();
        }
        if (percentile == 99.99) {
            return p9999();
        }
        throw new IllegalArgumentException("this summary keeps 50, 90, 99, 99.9 and 99.99;"
                + " " + percentile + " would need the histogram, which is not retained");
    }

    /**
     * @return how many times worse the 99th percentile is than the median. Above about 10 the
     *     distribution has a tail worth explaining — a mean or a median alone would not show it
     */
    public double tailRatio() {
        return p50 == 0 ? 0 : (double) p99 / p50;
    }

    /** @return whether anything was recorded */
    public boolean isEmpty() {
        return count == 0;
    }

    /** @return a fixed-width line for a report */
    public String format() {
        if (isEmpty()) {
            return String.format("%-16s (nothing recorded)", name);
        }
        return String.format("%-16s n=%-9d p50=%-9s p90=%-9s p99=%-9s p99.9=%-9s max=%s",
                name, count, ms(p50()), ms(p90()), ms(p99()), ms(p999()), ms(max()));
    }

    private static String ms(Duration d) {
        long micros = d.toNanos() / 1000;
        return micros < 1000 ? micros + "us" : String.format("%.1fms", micros / 1000.0);
    }

    @Override
    public String toString() {
        return "LatencySummary{" + format() + "}";
    }
}
