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

import org.acemq.workloads.Sample;

/**
 * Watches a scenario as it runs.
 *
 * <p>Callbacks run on the run's own sampling thread. Blocking in one spaces the readings out and
 * touches nothing else; an exception thrown from one is swallowed, because a run is an expensive
 * thing to lose and losing one to a chart would be a poor trade.
 */
@FunctionalInterface
public interface ScenarioListener {

    /** Does nothing, for a run nobody is watching. */
    ScenarioListener NONE = sample -> { };

    /**
     * A reading, taken about once a second, node by node.
     *
     * @param sample what every producer and every queue looked like at that moment
     */
    void onSample(ScenarioSample sample);

    /**
     * @param phase what the run is doing now
     */
    default void onPhase(Sample.Phase phase) {
    }

    /**
     * @param report the run's result
     */
    default void onFinished(ScenarioReport report) {
    }

    /**
     * @param failure why there is no report
     */
    default void onFailed(Throwable failure) {
    }
}
