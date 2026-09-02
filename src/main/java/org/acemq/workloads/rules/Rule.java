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
package org.acemq.workloads.rules;

import java.util.Optional;

import org.acemq.workloads.WorkloadReport;

/**
 * Something checked against a finished run.
 *
 * <p>A rule sees the whole {@link WorkloadReport} and returns a {@link Finding} or nothing.
 * Returning nothing is the normal case: a rule that fires on every run is one nobody reads,
 * which is the same failure mode as an alert that fires on every burst.
 */
@FunctionalInterface
public interface Rule {

    /**
     * @param report a finished run
     * @return a finding, or empty when this rule has nothing to say
     */
    Optional<Finding> check(WorkloadReport report);

    /** @return a short name for the report */
    default String name() {
        return getClass().getSimpleName();
    }
}
