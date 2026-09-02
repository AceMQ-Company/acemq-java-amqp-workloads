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

/**
 * How much a {@link Finding} matters, worst first.
 *
 * <p>{@link #INVALID} outranks {@link #FAILED} on purpose. A run that did not measure what it
 * claims cannot be said to have failed an objective — it has no result to judge, and reporting
 * "300,000/s: FAILED" when the generator itself could only produce 90,000 blames the broker for
 * the client's limits.
 */
public enum Severity {

    /**
     * The run did not measure what it set out to measure. Its numbers describe something else,
     * and no conclusion may be drawn from them.
     */
    INVALID,

    /** An objective was not met, and the measurement is sound. */
    FAILED,

    /** Something that changes how the result should be read. */
    WARNING,

    /** Worth knowing, and not a problem. */
    INFO
}
