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
 * A saga each consumed message runs: the library's {@code Saga}, with simulated steps.
 *
 * <p>Every step takes {@code stepTime} and fails with probability {@code failureRate}. A failure
 * runs the compensations of the steps already done, newest first; each takes {@code stepTime}
 * too and fails with probability {@code compensationFailureRate}. A compensation that failed is
 * what the library calls unresolved: a fact a person has to reconcile.
 *
 * @param steps steps per saga, at least one
 * @param stepTime how long each step and each compensation takes
 * @param failureRate probability that a step fails, between 0 and 1
 * @param compensationFailureRate probability that a compensation fails, between 0 and 1
 */
public record SagaLoad(int steps, Duration stepTime, double failureRate, double compensationFailureRate) {

    public SagaLoad {
        if (steps < 1) {
            throw new IllegalArgumentException("a saga needs at least one step");
        }
        Objects.requireNonNull(stepTime, "stepTime");
        if (stepTime.isNegative()) {
            throw new IllegalArgumentException("a saga's stepTime cannot be negative");
        }
        if (failureRate < 0 || failureRate > 1 || compensationFailureRate < 0 || compensationFailureRate > 1) {
            throw new IllegalArgumentException("a saga's failure rates are fractions between 0 and 1");
        }
    }

    @Override
    public String toString() {
        return "saga of " + steps + " steps of " + stepTime.toMillis() + "ms, failing " + failureRate
                + ", compensations failing " + compensationFailureRate;
    }
}
