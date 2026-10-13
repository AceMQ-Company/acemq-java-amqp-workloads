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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.acemq.amqp.security.Security;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Severity;

/**
 * Steps the offered rate up until something gives, to find the highest rate a broker sustains.
 *
 * <pre>{@code
 * RampReport ramp = Ramp.of(workload)
 *         .startAt(1_000)
 *         .stepBy(1_000)                 // or .stepByPercent(25)
 *         .stepFor(Duration.ofSeconds(20))
 *         .warmupEachStep(Duration.ofSeconds(5))
 *         .maxRate(100_000)
 *         .refine(3)
 *         .run("amqp://localhost");
 *
 * ramp.highestSustainedRate();   // the last step that met every rule and objective
 * ramp.stoppedBy();              // which rule ended it
 * }</pre>
 *
 * <h2>Every step is an ordinary run</h2>
 *
 * <p>Each step is the workload run at one fixed rate through the same open-loop engine as any
 * other run: latency is measured from when each message was <em>due</em>, so a broker that stalls
 * shows up as latency rather than as a generator that quietly slowed down with it. A ramp is not a
 * second engine with a moving rate; it is a loop over runs, so every step is judged by exactly the
 * rules a single run is.
 *
 * <h2>What stops it</h2>
 *
 * <ul>
 *   <li>a step with an {@code INVALID} finding — the generator could not offer the rate, or the
 *       broker raised a resource alarm. The ceiling found is then not the broker's, and the report
 *       says so;</li>
 *   <li>a step with a {@code FAILED} finding — an objective was missed, or a rule loaded from the
 *       file fired;</li>
 *   <li>{@code kept-pace}, added to every step: what was delivered fell more than
 *       {@link #tolerance(double)} short of what was offered. Without it a slow consumer would never
 *       stop a ramp, because a queue that fills is only a warning;</li>
 *   <li>a share of failed publishes above {@link #maxErrorRate(double)} (zero by default, so any
 *       failed publish stops it);</li>
 *   <li>{@link #maxRate(long)}, reached without anything firing. The ceiling is then "at least
 *       this", and the report says that rather than inventing a number.</li>
 * </ul>
 *
 * <p>Warnings never stop a ramp. Short steps warn that they were short, on every step.
 *
 * <h2>Refinement</h2>
 *
 * <p>{@link #refine(int)} bisects between the last good step and the first bad one. A refinement
 * step that comes straight after an overloaded one inherits whatever backlog that step left in
 * the queue, which can only make it look worse, never better: the ceiling it reports can be low,
 * not high.
 */
public final class Ramp {

    /** A ramp that runs this many steps without stopping is a configuration nobody meant. */
    static final int MAX_STEPS = 100;

    private final Workload workload;
    private long start;
    private long stepBy;
    private double stepPercent;
    private Duration stepFor;
    private Duration warmup;
    private long maxRate = Long.MAX_VALUE;
    private double maxErrorRate;
    private double tolerance = 0.05;
    private int refine;
    private long ceilingAtLeast;
    private volatile boolean stopRequested;

    private Ramp(Workload workload) {
        this.workload = Objects.requireNonNull(workload, "workload");
        // A run that ends only when stopped gives a step no length; check() asks for one.
        this.stepFor = workload.runsUntilStopped() ? null : workload.duration();
        this.warmup = workload.warmup();
        this.start = workload.publishers().rate();
    }

    /**
     * @param workload what to run at each step: topology, publishers, consumers, rules. Its rate,
     *     if it has one, is the default starting rate — each step replaces it, and an unthrottled
     *     workload is ramped like any other — its warm-up the default per step and its duration
     *     the default step length
     * @return a ramp over it
     */
    public static Ramp of(Workload workload) {
        return new Ramp(workload);
    }

    /** @param rate the first step's offered rate, in messages a second
     *  @return this */
    public Ramp startAt(long rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("a ramp starts at a positive rate");
        }
        this.start = rate;
        return this;
    }

    /** @param messagesPerSecond how much each step adds
     *  @return this */
    public Ramp stepBy(long messagesPerSecond) {
        if (messagesPerSecond <= 0) {
            throw new IllegalArgumentException("a ramp's step must be positive");
        }
        this.stepBy = messagesPerSecond;
        this.stepPercent = 0;
        return this;
    }

    /** @param percent how much each step adds, as a share of the step before, e.g. 25
     *  @return this */
    public Ramp stepByPercent(double percent) {
        if (percent <= 0) {
            throw new IllegalArgumentException("a ramp's step must be positive");
        }
        this.stepPercent = percent;
        this.stepBy = 0;
        return this;
    }

    /** @param duration the measured window of each step
     *  @return this */
    public Ramp stepFor(Duration duration) {
        this.stepFor = Objects.requireNonNull(duration, "duration");
        checkStepLength();
        return this;
    }

    // The pace check averages one-second readings and drops the first, which straddles the
    // reset; under five seconds it has too few left to mean anything.
    private void checkStepLength() {
        if (stepFor == null) {
            throw new IllegalArgumentException("a ramp's steps need a length, and the workload's"
                    + " runFor is until-stopped. Give the ramp a 'stepFor'");
        }
        if (stepFor.toSeconds() < 5) {
            throw new IllegalArgumentException("a ramp step needs at least 5s, and this one is "
                    + stepFor.toMillis() + "ms: the pace check averages one-second readings, and"
                    + " a shorter step leaves it too few");
        }
    }

    /** @param duration how long each step runs before it is measured
     *  @return this */
    public Ramp warmupEachStep(Duration duration) {
        this.warmup = Objects.requireNonNull(duration, "duration");
        return this;
    }

    /** @param rate no step is offered more than this; reaching it without a rule firing ends the
     *     ramp with "at least this"
     *  @return this */
    public Ramp maxRate(long rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("maxRate must be positive");
        }
        this.maxRate = rate;
        return this;
    }

    /** @param fraction the share of publishes that may fail before a step counts as bad, 0 to 1
     *  @return this */
    public Ramp maxErrorRate(double fraction) {
        if (fraction < 0 || fraction >= 1) {
            throw new IllegalArgumentException("maxErrorRate is a fraction from 0 up to 1");
        }
        this.maxErrorRate = fraction;
        return this;
    }

    /** @param fraction how far the delivered rate may fall short of the offered one, 0 to 1;
     *     default 0.05
     *  @return this */
    public Ramp tolerance(double fraction) {
        if (fraction < 0 || fraction >= 1) {
            throw new IllegalArgumentException("tolerance is a fraction from 0 up to 1");
        }
        this.tolerance = fraction;
        return this;
    }

    /** @param steps how many bisection steps to run between the last good and the first bad rate
     *  @return this */
    public Ramp refine(int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("refine cannot be negative");
        }
        this.refine = steps;
        return this;
    }

    /** @param rate an objective for the ramp as a whole: the highest sustained rate must reach it
     *  @return this */
    public Ramp ceilingAtLeast(long rate) {
        this.ceilingAtLeast = rate;
        return this;
    }

    /** Ends the ramp after the step now running. Safe from any thread. */
    public void stop() {
        stopRequested = true;
    }

    public Workload workload() {
        return workload;
    }

    public long start() {
        return start;
    }

    public long ceilingAtLeast() {
        return ceilingAtLeast;
    }

    /**
     * @param brokerUrl an AMQP URL
     * @return what each step measured, and where it stopped
     */
    public RampReport run(String brokerUrl) {
        return run(brokerUrl, null);
    }

    /**
     * @param brokerUrl an AMQP URL
     * @param security which certificates to believe, or null to let the URL decide
     * @return what each step measured, and where it stopped
     */
    public RampReport run(String brokerUrl, Security security) {
        return run(step -> step.run(brokerUrl, security));
    }

    /**
     * Runs the ramp with each step run by the caller — the command line uses this so a step can
     * be stopped by a signal and still report.
     *
     * @param runner runs one step's workload and returns its report
     * @return what each step measured, and where it stopped
     */
    public RampReport run(Function<Workload, WorkloadReport> runner) {
        check();

        List<RampReport.Step> steps = new ArrayList<>();
        long good = 0;
        long bad = 0;
        RampReport.Step stopper = null;
        String stoppedBy = null;

        long rate = start;
        while (true) {
            RampReport.Step step = runStep(runner, rate, false);
            steps.add(step);
            if (!step.sustained()) {
                bad = rate;
                stopper = step;
                stoppedBy = step.stoppedBy();
                break;
            }
            good = rate;
            if (stopRequested) {
                break;
            }
            if (rate >= maxRate) {
                stoppedBy = "maxRate";
                break;
            }
            if (steps.size() >= MAX_STEPS) {
                stoppedBy = "maxSteps";
                break;
            }
            rate = Math.min(maxRate, next(rate));
        }

        // Bisection: only meaningful once there is a good rate below a bad one.
        for (int i = 0; i < refine && good > 0 && bad > 0 && !stopRequested; i++) {
            long mid = good + (bad - good) / 2;
            if (mid <= good || mid >= bad) {
                break;
            }
            RampReport.Step step = runStep(runner, mid, true);
            steps.add(step);
            if (step.sustained()) {
                good = mid;
            } else {
                bad = mid;
            }
        }

        if (stopRequested) {
            stoppedBy = "stopped";
        }
        return new RampReport(this, steps, good, stoppedBy, stopper);
    }

    /**
     * Checks the settings hang together, without running anything.
     *
     * @return this
     * @throws IllegalArgumentException naming what is missing or contradictory
     */
    public Ramp check() {
        if (start <= 0) {
            throw new IllegalArgumentException("a ramp needs a starting rate: 'start', or a rate"
                    + " on the workload's publishers");
        }
        if (stepBy == 0 && stepPercent == 0) {
            throw new IllegalArgumentException("a ramp needs a step: an absolute rate or a"
                    + " percentage");
        }
        checkStepLength();
        if (start > maxRate) {
            throw new IllegalArgumentException("the ramp starts at " + start
                    + "/s, above its maxRate of " + maxRate + "/s");
        }
        return this;
    }

    private long next(long rate) {
        if (stepBy > 0) {
            return rate + stepBy;
        }
        // At least one message a second more, so a small rate and a small percentage still move.
        return Math.max(rate + 1, Math.round(rate * (1 + stepPercent / 100.0)));
    }

    private RampReport.Step runStep(Function<Workload, WorkloadReport> runner, long rate,
            boolean refinement) {
        WorkloadReport report = runner.apply(workload.step(rate, warmup, stepFor,
                keptPace(tolerance)));
        return new RampReport.Step(rate, refinement, report, judge(report));
    }

    /**
     * @return null when the step was sustained, otherwise what stopped it
     */
    private String judge(WorkloadReport report) {
        List<String> fired = new ArrayList<>();
        for (Finding finding : report.findings()) {
            if (finding.severity() != Severity.INVALID && finding.severity() != Severity.FAILED) {
                continue;
            }
            // Failed publishes are judged against the ramp's own allowance below, which is what
            // maxErrorRate is for; the default rule fires on the first one.
            if ("publishes-succeeded".equals(finding.rule())) {
                continue;
            }
            fired.add(finding.rule());
        }
        long attempts = report.published() + report.failed();
        double errorRate = attempts == 0 ? 0 : (double) report.failed() / attempts;
        if (errorRate > maxErrorRate) {
            fired.add(maxErrorRate == 0 ? "publishes-succeeded" : "maxErrorRate");
        }
        return fired.isEmpty() ? null : String.join(", ", fired);
    }

    /**
     * Did the system deliver what it was offered?
     *
     * <p>Judged on the one-second readings taken while the step was measuring, not on the report's
     * totals: the totals include the drain after the window, and a slow consumer given five seconds
     * of drain catches up on a step's backlog and reads as having kept pace. The first reading
     * straddles the counter reset at the start of the window and is dropped.
     */
    static Rule keptPace(double tolerance) {
        return report -> {
            boolean consumed = report.consumersEnabled();
            List<Sample> measuring = report.samples().stream()
                    .filter(s -> s.phase() == Sample.Phase.MEASURING)
                    .toList();
            double delivered;
            if (measuring.size() >= 2) {
                delivered = measuring.subList(1, measuring.size()).stream()
                        .mapToDouble(s -> consumed ? s.consumeRate() : s.publishRate())
                        .average().orElse(0);
            } else {
                delivered = consumed ? report.consumeRate() : report.achievedPublishRate();
            }
            double offered = report.offeredRate();
            if (offered <= 0 || delivered >= offered * (1 - tolerance)) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("kept-pace", Severity.FAILED,
                    String.format("offered %,.0f/s and %s %,.0f/s (%.0f%%) while measuring",
                            offered, consumed ? "consumed" : "published", delivered,
                            100.0 * delivered / offered),
                    String.format("the system fell more than %.0f%% behind the offered rate, so"
                            + " this rate is not sustained: a queue was growing", tolerance * 100)));
        };
    }

    @Override
    public String toString() {
        return "Ramp{" + workload.name() + " from " + start + "/s by "
                + (stepBy > 0 ? stepBy + "/s" : stepPercent + "%")
                + (maxRate == Long.MAX_VALUE ? "" : " to " + maxRate + "/s")
                + ", " + (stepFor == null ? "?" : stepFor.toSeconds()) + "s a step"
                + (refine > 0 ? ", refined " + refine + "x" : "") + "}";
    }
}
