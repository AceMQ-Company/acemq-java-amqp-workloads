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

import org.acemq.amqp.api.RetryPolicy;

/**
 * One step of a pipeline under load: the library's {@code Pipeline}, with a simulated handler.
 *
 * <pre>{@code
 * PipelineStage.named("enrich").handlerTime(Duration.ofMillis(5)).concurrency(8)
 *         .failureRate(0.01).retry(RetryPolicy.fixed(3, Duration.ofMillis(200)))
 * }</pre>
 *
 * @param name the step's name: its routing key and the suffix of its queue
 * @param handlerTime simulated work per message at this step
 * @param failureRate probability that a delivery fails here, between 0 and 1
 * @param endRate probability that this step ends the run early by returning nothing; not on
 *     the last step, which ends every run anyway
 * @param concurrency consumers for this step, or 0 for the workload's {@code concurrency}
 * @param retry the step's retry policy, or null for the library's rule: one attempt, then
 *     {@code .dlq}
 */
public record PipelineStage(String name, Duration handlerTime, double failureRate, double endRate,
        int concurrency, RetryPolicy retry) {

    public PipelineStage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handlerTime, "handlerTime");
        if (failureRate < 0 || failureRate > 1 || endRate < 0 || endRate > 1) {
            throw new IllegalArgumentException("step '" + name + "': failureRate and endRate are"
                    + " fractions between 0 and 1");
        }
        if (concurrency < 0) {
            throw new IllegalArgumentException("step '" + name + "': concurrency cannot be negative");
        }
    }

    /**
     * @param name the step's name
     * @return a step that does nothing, at the workload's concurrency, with no retry
     */
    public static PipelineStage named(String name) {
        return new PipelineStage(name, Duration.ZERO, 0, 0, 0, null);
    }

    public PipelineStage handlerTime(Duration time) {
        return new PipelineStage(name, time, failureRate, endRate, concurrency, retry);
    }

    public PipelineStage failureRate(double fraction) {
        return new PipelineStage(name, handlerTime, fraction, endRate, concurrency, retry);
    }

    public PipelineStage endRate(double fraction) {
        return new PipelineStage(name, handlerTime, failureRate, fraction, concurrency, retry);
    }

    public PipelineStage concurrency(int consumers) {
        return new PipelineStage(name, handlerTime, failureRate, endRate, consumers, retry);
    }

    public PipelineStage retry(RetryPolicy policy) {
        return new PipelineStage(name, handlerTime, failureRate, endRate, concurrency, policy);
    }

    @Override
    public String toString() {
        return name + (handlerTime.isZero() ? "" : " " + handlerTime.toMillis() + "ms")
                + (failureRate > 0 ? " failing " + failureRate : "")
                + (endRate > 0 ? " ending " + endRate : "")
                + (concurrency > 0 ? " x" + concurrency : "")
                + (retry != null ? " retry " + retry.maxAttempts() : "");
    }
}
