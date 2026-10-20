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
import java.util.Objects;

/**
 * How the load is consumed.
 *
 * <pre>{@code
 * .consumers(c -> c
 *         .concurrency(8)
 *         .prefetch(100)
 *         .handlerTime(Duration.ofMillis(2)))
 * }</pre>
 *
 * <p>{@link #handlerTime(Duration)} is the part people leave out and then wonder why the results
 * do not resemble production. A consumer that does nothing measures the broker; a consumer that
 * sleeps two milliseconds measures the broker <em>and</em> what happens when the application is
 * the slow part — which is the situation almost every real system is in.
 */
public final class ConsumerSpec {

    private int concurrency = 1;
    private int prefetch = 100;
    private Duration handlerTime = Duration.ZERO;
    private double failureRate = 0.0;
    private boolean enabled = true;
    private org.acemq.amqp.api.RetryPolicy retry;

    ConsumerSpec() {
    }

    /**
     * @param concurrency how many consumers to attach
     * @return this spec
     */
    public ConsumerSpec concurrency(int concurrency) {
        if (concurrency < 0) {
            throw new IllegalArgumentException("concurrency cannot be negative");
        }
        this.concurrency = concurrency;
        this.enabled = concurrency > 0;
        return this;
    }

    /**
     * How many unacknowledged messages a consumer will hold.
     *
     * <p>The most consequential number here, and the one most often left at a default that is
     * wrong in both directions. Too low and the consumer waits on the network between messages;
     * too high and one consumer takes the whole queue while its peers idle, and a restart
     * redelivers all of it.
     *
     * @param prefetch the limit, or 0 for unlimited
     * @return this spec
     */
    public ConsumerSpec prefetch(int prefetch) {
        if (prefetch < 0) {
            throw new IllegalArgumentException("prefetch cannot be negative; 0 means unlimited");
        }
        this.prefetch = prefetch;
        return this;
    }

    /**
     * Simulated work per message.
     *
     * <p>Implemented as a sleep, which models a handler waiting on a database or an HTTP call —
     * the common case. It does not model a handler that is CPU-bound, because a sleeping thread
     * does not compete for a core the way a busy one does.
     *
     * @param handlerTime how long each message takes to process
     * @return this spec
     */
    public ConsumerSpec handlerTime(Duration handlerTime) {
        this.handlerTime = Objects.requireNonNull(handlerTime, "handlerTime");
        return this;
    }

    /**
     * A fraction of messages the handler rejects.
     *
     * <p>For exercising retry and dead-lettering under load, which is where those paths behave
     * least like they do in a unit test.
     *
     * @param fraction between 0 and 1
     * @return this spec
     */
    public ConsumerSpec failureRate(double fraction) {
        if (fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("failureRate is a fraction between 0 and 1");
        }
        this.failureRate = fraction;
        return this;
    }

    /**
     * Runs with no consumers at all.
     *
     * <p>A publish-only workload. Useful for measuring ingest and for filling a queue before a
     * separate drain test — and note that end-to-end latency cannot be measured without a
     * consumer, so the report will say so rather than showing zeroes.
     *
     * @return this spec
     */
    public ConsumerSpec none() {
        this.concurrency = 0;
        this.enabled = false;
        return this;
    }

    public int concurrency() {
        return concurrency;
    }

    public int prefetch() {
        return prefetch;
    }

    public Duration handlerTime() {
        return handlerTime;
    }

    public double failureRate() {
        return failureRate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retries a failed handler on the library's ladder instead of dead-lettering it at once.
     *
     * <p>With {@link #failureRate(double)} this is the retry ladder under load: each delivery
     * fails with that probability, a failure goes round again after the policy's wait (in the
     * consumer below its broker threshold, in a {@code {queue}.retry.{delay}} rung at or above
     * it), and the last attempt's failure goes to {@code {queue}.dlq}. End-to-end latency is
     * measured to the delivery that succeeded, so it includes every wait. A message counts as
     * consumed once, when it succeeds or is dead-lettered.
     *
     * @param policy the schedule, for example {@code RetryPolicy.fixed(3, Duration.ofMillis(200))}
     * @return this spec
     */
    public ConsumerSpec retry(org.acemq.amqp.api.RetryPolicy policy) {
        this.retry = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** @return the retry policy, or null when a failure is dead-lettered on its first attempt */
    public org.acemq.amqp.api.RetryPolicy retry() {
        return retry;
    }

    @Override
    public String toString() {
        return enabled
                ? "ConsumerSpec{concurrency=" + concurrency + ", prefetch=" + prefetch
                        + ", handlerTime=" + handlerTime
                        + (failureRate > 0 ? ", failureRate=" + failureRate : "")
                        + (retry != null ? ", retry " + retry.maxAttempts() + " attempts after "
                                + retry.schedule() + ", broker from " + retry.brokerWaitThreshold() : "")
                        + "}"
                : "ConsumerSpec{none}";
    }
}
