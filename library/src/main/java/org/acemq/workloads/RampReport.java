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
import java.util.List;
import java.util.Optional;

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Severity;

/**
 * What a {@link Ramp} found: every step, the highest rate that met every rule and objective, and
 * what stopped it.
 *
 * <p>Read {@link #isValid()} before quoting the ceiling. A ramp stopped by an {@code INVALID} step
 * found the generator's limit, or a resource alarm's, and the number below it is a floor for the
 * broker rather than its ceiling.
 */
public final class RampReport {

    /**
     * One step.
     *
     * @param rate the offered rate
     * @param refinement whether this was a bisection step rather than one of the climb
     * @param report what the step measured
     * @param stoppedBy the rules that made it bad, comma-separated, or null when it was sustained
     */
    public record Step(long rate, boolean refinement, WorkloadReport report, String stoppedBy) {

        /** @return whether every rule and objective held at this rate */
        public boolean sustained() {
            return stoppedBy == null;
        }
    }

    private final Ramp ramp;
    private final List<Step> steps;
    private final long highest;
    private final String stoppedBy;
    private final Step stopper;

    RampReport(Ramp ramp, List<Step> steps, long highest, String stoppedBy, Step stopper) {
        this.ramp = ramp;
        this.steps = List.copyOf(steps);
        this.highest = highest;
        this.stoppedBy = stoppedBy;
        this.stopper = stopper;
    }

    public String name() {
        return ramp.workload().name();
    }

    public Ramp ramp() {
        return ramp;
    }

    /** @return every step in the order it ran, climb first and then any refinement */
    public List<Step> steps() {
        return steps;
    }

    /** @return the highest offered rate that met every rule and objective, or 0 if none did */
    public long highestSustainedRate() {
        return highest;
    }

    /**
     * @return what ended the climb: the rules that fired on the first bad step, or
     *     {@code maxRate}, {@code maxErrorRate}, {@code maxSteps} or {@code stopped}
     */
    public String stoppedBy() {
        return stoppedBy;
    }

    /** @return the first step that was not sustained, if the climb found one */
    public Optional<Step> stoppingStep() {
        return Optional.ofNullable(stopper);
    }

    /** @return whether the climb reached maxRate without anything firing */
    public boolean reachedMaxRate() {
        return "maxRate".equals(stoppedBy);
    }

    /**
     * @return false when the climb was ended by an invalid step — the generator fell behind its
     *     schedule or the broker raised an alarm — so the ceiling is not the broker's
     */
    public boolean isValid() {
        return stopper == null || stopper.report().isValid();
    }

    /**
     * @return with {@code ceilingAtLeast} set, whether the highest sustained rate reached it;
     *     otherwise whether the ramp was valid and sustained at least its first step
     */
    public boolean passed() {
        if (ramp.ceilingAtLeast() > 0) {
            return highest >= ramp.ceilingAtLeast();
        }
        return isValid() && highest > 0;
    }

    /** @return PASSED, FAILED or INVALID, the way the command line says it */
    public String verdict() {
        return passed() ? "PASSED" : !isValid() ? "INVALID" : "FAILED";
    }

    /** @return the finding that ended the climb, worst first, if one did */
    public Optional<Finding> stoppingFinding() {
        if (stopper == null) {
            return Optional.empty();
        }
        return stopper.report().findings().stream()
                .filter(f -> f.severity() == Severity.INVALID || f.severity() == Severity.FAILED)
                .findFirst();
    }

    /** @return a sentence on why the climb ended */
    public String stopReason() {
        if (stopper != null) {
            String why = stoppingFinding().map(Finding::observation).orElse(stoppedBy);
            return String.format("%s at %,d/s: %s", stoppedBy, stopper.rate(), why);
        }
        return switch (stoppedBy == null ? "" : stoppedBy) {
            case "maxRate" -> String.format("reached maxRate (%,d/s) with every rule holding, so"
                    + " the ceiling is at least that", highest);
            case "maxSteps" -> "ran " + Ramp.MAX_STEPS + " steps without anything firing";
            case "stopped" -> "stopped on request";
            default -> String.valueOf(stoppedBy);
        };
    }

    /** @return the whole ramp, formatted for a terminal */
    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("ramp: ").append(name()).append('\n');
        out.append("  ").append(ramp).append('\n');
        out.append("  ").append(ramp.workload().topology()).append('\n');
        out.append("  ").append(ramp.workload().consumers()).append('\n');
        out.append('\n');
        out.append(String.format("    %-12s %-9s %12s %12s %9s %9s %9s %9s  %s%n", "offered",
                "result", "published", "consumed", "p50", "p90", "p99", "p99.9", "stopped by"));
        for (Step step : steps) {
            WorkloadReport r = step.report();
            LatencySummary e2e = r.endToEnd();
            out.append(String.format("  %s %-12s %-9s %12s %12s %9s %9s %9s %9s  %s%n",
                    step.refinement() ? "*" : " ",
                    String.format("%,d/s", step.rate()),
                    step.sustained() ? "ok" : r.isValid() ? "FAILED" : "INVALID",
                    String.format("%,.0f/s", r.achievedPublishRate()),
                    r.consumersEnabled() ? String.format("%,.0f/s", r.consumeRate()) : "-",
                    cell(e2e.isEmpty() ? null : e2e.p50()),
                    cell(e2e.isEmpty() ? null : e2e.p90()),
                    cell(e2e.isEmpty() ? null : e2e.p99()),
                    cell(e2e.isEmpty() ? null : e2e.p999()),
                    step.sustained() ? "" : step.stoppedBy()));
        }
        if (steps.stream().anyMatch(Step::refinement)) {
            out.append("  * refinement step, between the last good rate and the first bad one\n");
        }
        out.append('\n');
        out.append(String.format("  highest sustained rate: %s%n", highest == 0 ? "none"
                : String.format("%,d/s", highest)));
        out.append("  stopped by: ").append(stopReason()).append('\n');
        if (!isValid()) {
            out.append("  the step that stopped it was INVALID: this is the generator's or an"
                    + " alarm's limit, not the broker's ceiling\n");
        }
        if (ramp.ceilingAtLeast() > 0) {
            out.append(String.format("  objective: ceiling at least %,d/s%n", ramp.ceilingAtLeast()));
        }
        out.append("  result: ").append(verdict()).append('\n');
        return out.toString();
    }

    private static String cell(Duration d) {
        if (d == null) {
            return "-";
        }
        long nanos = d.toNanos();
        return nanos < 1_000_000 ? (nanos / 1_000) + "us"
                : String.format("%.1fms", nanos / 1_000_000.0);
    }

    @Override
    public String toString() {
        return "RampReport{" + name() + ": highest sustained " + highest + "/s, stopped by "
                + stoppedBy + "}";
    }
}
