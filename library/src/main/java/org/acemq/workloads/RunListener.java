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

/**
 * Watches a run as it happens.
 *
 * <p>Every method has a default, so an implementation takes only what it wants:
 *
 * <pre>{@code
 * RunHandle run = workload.start("amqp://localhost", sample ->
 *         System.out.printf("%ds  %,.0f/s out  %,.0f/s in%n",
 *                 sample.elapsed().toSeconds(), sample.publishRate(), sample.consumeRate()));
 *
 * WorkloadReport report = run.report().join();
 * }</pre>
 *
 * <p><strong>Callbacks run on the run's own sampling thread.</strong> Blocking in one delays the
 * next reading and nothing else — the publishers and consumers are unaffected — but a listener
 * that writes to a slow socket will space the readings out. Hand the sample to a queue and return.
 *
 * <p>An exception thrown from a callback is swallowed and does not stop the run. A run is an
 * expensive thing to lose, and losing one because a progress bar threw would be a poor trade.
 */
@FunctionalInterface
public interface RunListener {

    /** Does nothing, for a run nobody is watching. */
    RunListener NONE = sample -> { };

    /**
     * A reading, taken about once a second.
     *
     * @param sample what the run looked like at that moment
     */
    void onSample(Sample sample);

    /**
     * The run moved from one phase to another.
     *
     * @param phase what it is doing now
     */
    default void onPhase(Sample.Phase phase) {
    }

    /**
     * The run finished, whether it passed, failed or was stopped early.
     *
     * @param report the same report {@link RunHandle#report()} completes with
     */
    default void onFinished(WorkloadReport report) {
    }

    /**
     * The run ended without producing a report.
     *
     * <p>A broker that cannot be reached, a topology the broker refuses, a queue that exists with
     * different arguments. The run is over and there is nothing to read.
     *
     * @param failure what went wrong
     */
    default void onFailed(Throwable failure) {
    }
}
