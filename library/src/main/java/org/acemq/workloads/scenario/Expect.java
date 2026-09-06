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

import java.time.Duration;

/**
 * What a scenario is asked to prove.
 *
 * <p>Without these a scenario can only ever describe what happened, which is enough for somebody
 * watching and not enough for a pipeline: a build cannot be failed on a description. An
 * expectation turns a run into an answer — this queue's p99 stays under 50ms, this producer
 * loses nothing, this backlog does not grow — and the command line's exit code follows.
 *
 * <p><strong>Per node, not for the whole run.</strong> A scenario's interesting property is
 * usually asymmetric: the audit leg may lag as much as it likes while the fulfilment leg must
 * not. An overall p99 would average exactly the distinction worth keeping.
 *
 * <p>Every field is optional. What is not stated is not checked, because an expectation nobody
 * wrote is not one the run failed.
 */
public final class Expect {

    private Duration p50Below;
    private Duration p99Below;
    private Duration p999Below;
    private Long consumeRateAtLeast;
    private Long achievedRateAtLeast;
    private Integer withinPercentOfOffered;
    private Boolean noFailures;
    private Boolean noBacklog;

    /**
     * @param limit the median must stay under this
     * @return this expectation
     */
    public Expect p50Below(Duration limit) {
        this.p50Below = limit;
        return this;
    }

    /**
     * @param limit the 99th percentile must stay under this
     * @return this expectation
     */
    public Expect p99Below(Duration limit) {
        this.p99Below = limit;
        return this;
    }

    /**
     * The tail, which is where a queue's real behaviour shows.
     *
     * @param limit the 99.9th percentile must stay under this
     * @return this expectation
     */
    public Expect p999Below(Duration limit) {
        this.p999Below = limit;
        return this;
    }

    /**
     * @param rate this queue's consumers must handle at least this many a second
     * @return this expectation
     */
    public Expect consumeRateAtLeast(long rate) {
        this.consumeRateAtLeast = rate;
        return this;
    }

    /**
     * @param rate this producer must actually offer at least this many a second
     * @return this expectation
     */
    public Expect achievedRateAtLeast(long rate) {
        this.achievedRateAtLeast = rate;
        return this;
    }

    /**
     * How far the achieved rate may fall short of the offered one.
     *
     * <p>The useful shape for a producer, because the number that matters is relative: a
     * generator that offered 19,400 of 20,000 did its job, and one that offered 12,000 did not,
     * and neither is expressible as a fixed floor that survives changing the rate.
     *
     * @param percent the allowed shortfall, so 5 means at least 95% of what was asked for
     * @return this expectation
     */
    public Expect withinPercentOfOffered(int percent) {
        this.withinPercentOfOffered = percent;
        return this;
    }

    /**
     * @param required whether every publish must succeed
     * @return this expectation
     */
    public Expect noFailures(boolean required) {
        this.noFailures = required;
        return this;
    }

    /**
     * The queue must not be deeper at the end than it was at the start.
     *
     * <p>Not checked for a stream, which retains what it has served and is always "deeper".
     *
     * @param required whether the backlog must not grow
     * @return this expectation
     */
    public Expect noBacklog(boolean required) {
        this.noBacklog = required;
        return this;
    }

    public Duration p50BelowValue() {
        return p50Below;
    }

    public Duration p99BelowValue() {
        return p99Below;
    }

    public Duration p999BelowValue() {
        return p999Below;
    }

    public Long consumeRateAtLeastValue() {
        return consumeRateAtLeast;
    }

    public Long achievedRateAtLeastValue() {
        return achievedRateAtLeast;
    }

    public Integer withinPercentOfOfferedValue() {
        return withinPercentOfOffered;
    }

    public boolean requiresNoFailures() {
        return Boolean.TRUE.equals(noFailures);
    }

    public boolean requiresNoBacklog() {
        return Boolean.TRUE.equals(noBacklog);
    }

    /** @return whether anything at all is being asked for */
    public boolean isEmpty() {
        return p50Below == null && p99Below == null && p999Below == null
                && consumeRateAtLeast == null && achievedRateAtLeast == null
                && withinPercentOfOffered == null
                && !requiresNoFailures() && !requiresNoBacklog();
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "nothing in particular";
        }
        StringBuilder out = new StringBuilder();
        if (p50Below != null) {
            out.append("p50 < ").append(p50Below.toMillis()).append("ms, ");
        }
        if (p99Below != null) {
            out.append("p99 < ").append(p99Below.toMillis()).append("ms, ");
        }
        if (p999Below != null) {
            out.append("p99.9 < ").append(p999Below.toMillis()).append("ms, ");
        }
        if (consumeRateAtLeast != null) {
            out.append("at least ").append(consumeRateAtLeast).append("/s consumed, ");
        }
        if (achievedRateAtLeast != null) {
            out.append("at least ").append(achievedRateAtLeast).append("/s offered, ");
        }
        if (withinPercentOfOffered != null) {
            out.append("within ").append(withinPercentOfOffered).append("% of the offered rate, ");
        }
        if (requiresNoFailures()) {
            out.append("no failed publishes, ");
        }
        if (requiresNoBacklog()) {
            out.append("no growing backlog, ");
        }
        return out.substring(0, out.length() - 2);
    }
}
