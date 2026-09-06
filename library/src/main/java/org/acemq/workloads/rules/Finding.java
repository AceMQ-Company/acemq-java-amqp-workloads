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

import java.util.Objects;
import java.util.Optional;

/**
 * One thing a rule noticed, with the measurement that made it say so.
 *
 * <h2>Evidence, not advice</h2>
 *
 * <p>Every finding must carry the observation it came from. This is a deliberate constraint on
 * what this library is willing to say.
 *
 * <p>A load tool that prints "increase prefetch to 250" is guessing. It does not know the
 * handler's processing time distribution, the message size, the network, the disk, or what else
 * shares the broker — and a confident recommendation from a tool is followed, which makes a
 * wrong one worse than silence.
 *
 * <p>What a tool <em>can</em> say honestly is what it measured and what that rules in or out:
 * "publishers offered 300,000/s and achieved 180,000/s; connections were blocked for 43% of the
 * run and the free-disk alarm was in effect". That is defensible, it is actionable, and every
 * word of it is an observation.
 *
 * <p>So a finding has an {@link #observation()} — what was measured — and an
 * {@link #implication()} — what that means for the result. Neither is a prescription.
 */
public final class Finding {

    private final String rule;
    private final Severity severity;
    private final String observation;
    private final String implication;
    private final String detail;

    Finding(String rule, Severity severity, String observation, String implication, String detail) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.implication = Objects.requireNonNull(implication, "implication");
        this.detail = detail;
    }

    /**
     * @param rule which rule fired
     * @param severity how much it matters
     * @param observation what was measured, with the numbers in it
     * @param implication what that means for the result
     * @return the finding
     */
    public static Finding of(String rule, Severity severity, String observation, String implication) {
        return new Finding(rule, severity, observation, implication, null);
    }

    /**
     * @param rule which rule fired
     * @param severity how much it matters
     * @param observation what was measured
     * @param implication what it means
     * @param detail supporting numbers, for a verbose report
     * @return the finding
     */
    public static Finding of(String rule, Severity severity, String observation,
            String implication, String detail) {
        return new Finding(rule, severity, observation, implication, detail);
    }

    public String rule() {
        return rule;
    }

    public Severity severity() {
        return severity;
    }

    /** @return what was measured. Always contains numbers, because a rule without them is an opinion */
    public String observation() {
        return observation;
    }

    /** @return what the observation means for this result */
    public String implication() {
        return implication;
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    /** @return whether this finding means the run did not measure what it set out to measure */
    public boolean invalidatesRun() {
        return severity == Severity.INVALID;
    }

    /** @return a line for the report */
    public String format() {
        return "[" + severity + "] " + rule + "\n"
                + "    observed:  " + observation + "\n"
                + "    means:     " + implication
                + (detail == null ? "" : "\n    detail:    " + detail);
    }

    @Override
    public String toString() {
        return "Finding{" + severity + " " + rule + ": " + observation + "}";
    }
}
