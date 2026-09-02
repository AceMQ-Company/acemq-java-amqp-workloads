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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rules that run on every workload, whether or not anybody asked for them.
 *
 * <h2>Validity first</h2>
 *
 * <p>The most valuable rules here are not the ones that judge the broker. They are the ones that
 * decide whether the run measured the broker <em>at all</em>.
 *
 * <p>A load test that quietly failed to offer the load, or that spent half its time with
 * publishers blocked by a disk alarm, still produces a full set of confident numbers. Those
 * numbers describe something — just not the thing anybody wanted to know. {@link #generatorKeptUp()}
 * and {@link #brokerWasNotBlocked()} exist to say so out loud, and they return
 * {@link Severity#INVALID} rather than a failure, because blaming the broker for the client's
 * limits is worse than reporting nothing.
 *
 * <h2>Diagnosis, not prescription</h2>
 *
 * <p>Nothing here says "set prefetch to 250". A tool cannot know your handler's processing time,
 * your message sizes, or what else shares the broker, and a confident wrong recommendation gets
 * followed. Every rule reports what it measured and what that rules in or out.
 */
public final class Rules {

    private Rules() {
    }

    /**
     * Did the generator actually offer the rate it was asked for?
     *
     * <p>The single most important check in the set, and the one homemade load tools do not
     * have. If message <em>n</em> was due at t and went out at t+4s, the offered rate was not
     * the configured rate — and every throughput number from the run describes what the client
     * managed, not what the broker could take.
     *
     * <p>The cause is usually the client: too few publisher threads, a saturated CPU, or a
     * network the generator shares with something else. It can also be the broker applying
     * back-pressure, which is why this reports the observation rather than the cause.
     *
     * @return the rule
     */
    public static Rule generatorKeptUp() {
        return report -> {
            if (report.spec().publishers().isUnthrottled() || report.sendLag().isEmpty()) {
                return Optional.empty();
            }
            Duration p99 = report.sendLag().p99();
            // A tenth of a second behind schedule at p99 is the point at which the offered rate
            // and the configured rate have stopped being the same number.
            if (p99.toMillis() < 100) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("generator-kept-up", Severity.INVALID,
                    String.format("publishes ran %s behind their own schedule at p99 (max %s);"
                                    + " the configured rate was %,d/s and the achieved rate was %,.0f/s",
                            Objective.human(p99), Objective.human(report.sendLag().max()),
                            report.spec().publishers().rate(), report.achievedPublishRate()),
                    "the configured load was never offered, so this run does not show what the"
                            + " broker can take. Raise publisher threads, or run the generator on"
                            + " a machine that is not also the broker, and repeat",
                    "publisher threads=" + report.spec().publishers().threadCount()
                            + ", send lag p50=" + Objective.human(report.sendLag().p50())));
        };
    }

    /**
     * Was the broker refusing publishes during the run?
     *
     * <p>A memory or disk alarm blocks publishing connections. The broker keeps running, the
     * consumers keep working, and the publishers stop — so the run measures how long the alarm
     * lasted rather than how fast the broker is.
     *
     * @return the rule
     */
    public static Rule brokerWasNotBlocked() {
        return report -> {
            if (!report.wasBlocked()) {
                return Optional.empty();
            }
            double share = 100.0 * report.blockedNanos() / Math.max(1, report.duration().toNanos());
            return Optional.of(Finding.of("broker-not-blocked", Severity.INVALID,
                    String.format("the connection was in a blocked state for %.0f%% of the run"
                                    + " (%s of %s)%s",
                            share, Objective.human(Duration.ofNanos(report.blockedNanos())),
                            Objective.human(report.duration()),
                            report.blockedReason().map(r -> ", reported as: " + r).orElse("")),
                    "the broker hit a resource alarm and refused publishes for part of the run."
                            + " The throughput here is the alarm's, not the broker's — this is"
                            + " broker capacity, not application load"));
        };
    }

    /**
     * Did the consumers keep up with the publishers?
     *
     * <p>Not a failure on its own: a run that deliberately fills a queue is doing this on
     * purpose. It is a warning because the end-to-end latency of a growing queue is a function
     * of how long the run lasted, not of how fast the broker is — run it for twice as long and
     * the p99 doubles.
     *
     * @return the rule
     */
    public static Rule consumersKeptUp() {
        return report -> {
            if (!report.consumersEnabled() || report.published() == 0) {
                return Optional.empty();
            }
            long backlog = report.published() - report.consumed();
            // A tenth of the run's output still queued is a queue that is growing, not a queue
            // absorbing jitter.
            if (backlog <= report.published() / 10) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("consumers-kept-up", Severity.WARNING,
                    String.format("%,d messages were published and %,d consumed, leaving %,d"
                                    + " (%.0f%%) still queued at the end",
                            report.published(), report.consumed(), backlog,
                            100.0 * backlog / report.published()),
                    "the consumers did not keep up, so the queue grew for the whole run. The"
                            + " end-to-end latency below is a function of the run's length rather"
                            + " than of the broker: a longer run would report a worse p99 from the"
                            + " same system",
                    "consumer concurrency=" + report.spec().consumers().concurrency()
                            + ", prefetch=" + report.spec().consumers().prefetch()
                            + ", handler time=" + report.spec().consumers().handlerTime()));
        };
    }

    /**
     * @return a warning when publisher confirms were off, because the throughput then counts
     *     messages handed to a socket rather than messages the broker accepted
     */
    public static Rule confirmsWereOn() {
        return report -> {
            if (report.spec().publishers().confirms()) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("confirms-were-on", Severity.WARNING,
                    String.format("publisher confirms were off, and %,d messages were counted as"
                            + " published", report.published()),
                    "a publish without confirms is a message handed to the socket, not one the"
                            + " broker has accepted. This rate is an upper bound that a durable"
                            + " configuration will not reproduce"));
        };
    }

    /**
     * @return a warning when the latency distribution has a long tail, which a median hides
     *     entirely and which is usually where the interesting behaviour is
     */
    public static Rule tailIsNotExtreme() {
        return report -> {
            if (report.endToEnd().isEmpty() || report.endToEnd().count() < 1000) {
                return Optional.empty();
            }
            double ratio = report.endToEnd().tailRatio();
            if (ratio < 10) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("tail-is-not-extreme", Severity.WARNING,
                    String.format("p99 is %.0f times the median (p50=%s, p99=%s, max=%s)",
                            ratio, Objective.human(report.endToEnd().p50()),
                            Objective.human(report.endToEnd().p99()),
                            Objective.human(report.endToEnd().max())),
                    "most messages are fast and a small fraction are far slower. A mean or median"
                            + " latency from this run would describe almost nobody's experience"));
        };
    }

    /**
     * @return a warning when the run was too short for its numbers to settle. A ten-second run
     *     measures JIT compilation and connection setup as much as it measures the broker
     */
    public static Rule runWasLongEnough() {
        return report -> {
            if (report.duration().toSeconds() >= 30) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("run-was-long-enough", Severity.WARNING,
                    "the measured window was " + Objective.human(report.duration()),
                    "short runs measure JIT compilation, connection setup and the first garbage"
                            + " collection as much as they measure the broker. Thirty seconds is"
                            + " a floor, and minutes are better for anything being promised to"
                            + " somebody"));
        };
    }

    /**
     * @return a note when publishes failed outright, which is different from being slow
     */
    public static Rule publishesSucceeded() {
        return report -> {
            if (report.failed() == 0) {
                return Optional.empty();
            }
            return Optional.of(Finding.of("publishes-succeeded", Severity.FAILED,
                    String.format("%,d of %,d publishes failed (%.2f%%)",
                            report.failed(), report.published() + report.failed(),
                            100.0 * report.failed() / Math.max(1, report.published() + report.failed())),
                    "these were refused or errored rather than delayed. A nacked publish is a"
                            + " message the broker did not take responsibility for"));
        };
    }

    /** @return every rule above, which is what a workload runs unless told otherwise */
    public static List<Rule> defaults() {
        List<Rule> rules = new ArrayList<>();
        rules.add(generatorKeptUp());
        rules.add(brokerWasNotBlocked());
        rules.add(publishesSucceeded());
        rules.add(consumersKeptUp());
        rules.add(confirmsWereOn());
        rules.add(tailIsNotExtreme());
        rules.add(runWasLongEnough());
        return rules;
    }
}
