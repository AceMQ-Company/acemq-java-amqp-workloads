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
 * The consumers on one queue.
 *
 * <p>Switching this off is not the same as removing it: the queue keeps filling and the run
 * measures what a backlog costs, which is usually the question somebody had.
 */
public final class ConsumerGroupNode implements Node {

    private final QueueNode queue;
    private int concurrency = 1;
    private int prefetch = 100;
    private Duration handlerTime = Duration.ZERO;
    private double failureRate = 0.0;
    private boolean enabled = true;

    ConsumerGroupNode(QueueNode queue) {
        this.queue = queue;
    }

    /**
     * @param concurrency how many consumers on this queue
     * @return this group
     */
    public ConsumerGroupNode concurrency(int concurrency) {
        if (concurrency < 0) {
            throw new IllegalArgumentException("concurrency cannot be negative");
        }
        this.concurrency = concurrency;
        return this;
    }

    /**
     * How many unacknowledged messages each consumer may hold.
     *
     * <p>The number most often wrong in production, in both directions: too low and a consumer
     * waits a round trip between messages; too high and one consumer takes work it cannot get
     * through while others idle. Measuring is the only way to know which side you are on.
     *
     * @param prefetch the window
     * @return this group
     */
    public ConsumerGroupNode prefetch(int prefetch) {
        this.prefetch = prefetch;
        return this;
    }

    /**
     * @param handlerTime how long the handler pretends to work for
     * @return this group
     */
    public ConsumerGroupNode handlerTime(Duration handlerTime) {
        this.handlerTime = handlerTime == null ? Duration.ZERO : handlerTime;
        return this;
    }

    /**
     * @param fraction how often the handler throws, from 0 to 1
     * @return this group
     */
    public ConsumerGroupNode failureRate(double fraction) {
        if (fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("a failure rate is a fraction between 0 and 1");
        }
        this.failureRate = fraction;
        return this;
    }

    /**
     * @param enabled whether these consumers run
     * @return this group
     */
    public ConsumerGroupNode enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** @return this group, switched off, so the queue grows */
    public ConsumerGroupNode none() {
        return enabled(false);
    }

    @Override
    public String name() {
        return queue.name() + " consumers";
    }

    /** @return the queue these consumers read */
    public String queueName() {
        return queue.name();
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

    @Override
    public boolean isEnabled() {
        return enabled && concurrency > 0;
    }

    @Override
    public String toString() {
        return isEnabled()
                ? concurrency + " consumers, prefetch " + prefetch
                : "no consumers";
    }
}
