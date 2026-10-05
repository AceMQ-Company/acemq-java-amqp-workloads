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
package org.acemq.workloads.studio.console;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.acemq.workloads.WorkloadReport;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Rules;

/**
 * The library's rules and the workload's objectives, evaluated on one finished run, each with
 * the numbers it was checked with.
 *
 * <p>A rule in the library speaks only when it has something to say: a run that passed it gets
 * nothing back. The console shows every rule anyway, because "passed, with these numbers" is the
 * evidence, and a list that only names failures cannot be told apart from one that never ran.
 * The verdict always comes from the library's own {@link Rule#check}; only the numbers beside a
 * pass are read here.
 */
final class Evidence {

    private Evidence() {
    }

    /** One rule, its verdict, and what it was decided on. */
    record Entry(String id, String title, String plain, String status, String evidence,
            String observation, String implication) {
    }

    private record Known(String id, String title, String plain, Rule rule, boolean applies,
            String evidence) {
    }

    static List<Entry> of(WorkloadReport r, boolean noMessagesLostExpected) {
        LatencySummary lag = r.sendLag();
        LatencySummary e2e = r.endToEnd();
        long backlog = r.published() - r.consumed();
        List<Known> known = List.of(
                new Known("generator-kept-up", "The generator offered the load it was asked for",
                        "Publishes that fall behind their own schedule mean the configured load"
                                + " was never offered, so the run says nothing about the broker.",
                        Rules.generatorKeptUp(),
                        !r.spec().publishers().isUnthrottled() && !lag.isEmpty(),
                        r.spec().publishers().isUnthrottled() ? "unthrottled: there is no schedule"
                                : "send lag p99 " + ms(lag.p99()) + " · limit 100 ms · "
                                        + String.format("%,d/s asked, %,.0f/s achieved",
                                                r.spec().publishers().rate(),
                                                r.achievedPublishRate())),
                new Known("broker-not-blocked", "The broker never blocked the publisher",
                        "A resource alarm refuses publishes; throughput measured under one is the"
                                + " alarm's, not the broker's.",
                        Rules.brokerWasNotBlocked(), true,
                        "blocked " + ms(Duration.ofNanos(r.blockedNanos())) + " of "
                                + ms(r.duration())),
                new Known("publishes-succeeded", "No publish failed",
                        "A failed publish is one the broker did not take responsibility for. A"
                                + " refused one was never sent and is counted apart.",
                        Rules.publishesSucceeded(), true,
                        String.format("failed %,d of %,d", r.failed(),
                                r.published() + r.failed())),
                new Known("consumers-kept-up", "Consumers kept up",
                        "If more than a tenth of what was published is still queued, latency"
                                + " measures the run's length rather than the broker.",
                        Rules.consumersKeptUp(), r.consumersEnabled() && r.published() > 0,
                        String.format("published %,d · consumed %,d · backlog %,d · limit 10%%",
                                r.published(), r.consumed(), backlog)),
                new Known("confirms-were-on", "Publisher confirms were on",
                        "Without confirms a publish is a message handed to the socket, and the"
                                + " rate is an upper bound a durable setup will not reproduce.",
                        Rules.confirmsWereOn(), true,
                        "confirms " + (r.spec().publishers().confirms() ? "on" : "off")),
                new Known("tail-is-not-extreme", "p99 stays within 10× the median",
                        "A tail far above the median means a mean or median describes almost"
                                + " nobody's experience.",
                        Rules.tailIsNotExtreme(), !e2e.isEmpty() && e2e.count() >= 1000,
                        e2e.isEmpty() ? "no end-to-end latency recorded"
                                : String.format("p50 %s · p99 %s · %.1f× · n=%,d", ms(e2e.p50()),
                                        ms(e2e.p99()), e2e.tailRatio(), e2e.count())),
                new Known("run-was-long-enough", "The measured window was at least 30 s",
                        "Short runs measure JIT compilation and connection setup as much as the"
                                + " broker.",
                        Rules.runWasLongEnough(), true, "measured " + ms(r.duration())));

        List<Entry> out = new ArrayList<>();
        for (Known k : known) {
            Optional<Finding> finding = k.rule().check(r);
            String status = finding.map(f -> status(f)).orElse(k.applies() ? "passed" : "n/a");
            out.add(new Entry(k.id(), k.title(), k.plain(), status, k.evidence(),
                    finding.map(Finding::observation).orElse(null),
                    finding.map(Finding::implication).orElse(null)));
        }

        // The objectives the file asked for. Each of them reports on a pass as well, so the
        // finding is the evidence, apart from noMessagesLost, which is silent when it holds.
        Set<String> defaults = Set.of("generator-kept-up", "broker-not-blocked",
                "publishes-succeeded", "consumers-kept-up", "confirms-were-on",
                "tail-is-not-extreme", "run-was-long-enough");
        boolean lostReported = false;
        for (Finding f : r.findings()) {
            if (defaults.contains(f.rule())) {
                continue;
            }
            lostReported |= f.rule().equals("no-messages-lost");
            out.add(new Entry(f.rule(), objectiveTitle(f.rule()), "An objective this workload"
                    + " file set: the build fails on it.", status(f),
                    f.observation() + f.detail().map(d -> " · " + d).orElse(""),
                    f.observation(), f.implication()));
        }
        if (noMessagesLostExpected && !lostReported) {
            out.add(new Entry("no-messages-lost", "Nothing confirmed is lost",
                    "An objective this workload file set: every message the broker confirmed was"
                            + " consumed.",
                    r.consumersEnabled() ? "passed" : "n/a",
                    String.format("confirmed %,d · consumed %,d", r.confirmed(), r.consumed()),
                    null, null));
        }
        return out;
    }

    static Map<String, Object> latency(LatencySummary s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", s.count());
        m.put("minMs", millis(s.min()));
        m.put("p50Ms", millis(s.p50()));
        m.put("p90Ms", millis(s.p90()));
        m.put("p99Ms", millis(s.p99()));
        m.put("p999Ms", millis(s.p999()));
        m.put("p9999Ms", millis(s.p9999()));
        m.put("maxMs", millis(s.max()));
        return m;
    }

    private static double millis(Duration d) {
        return Math.round(d.toNanos() / 1_000.0) / 1_000.0;
    }

    private static String status(Finding f) {
        return switch (f.severity()) {
            case INFO -> "passed";
            case WARNING -> "warning";
            case FAILED -> "failed";
            case INVALID -> "invalid";
        };
    }

    private static String objectiveTitle(String rule) {
        if (rule.startsWith("throughput>=")) {
            return "Throughput at least " + rule.substring("throughput>=".length()) + " msg/s";
        }
        if (rule.equals("no-messages-lost")) {
            return "Nothing confirmed is lost";
        }
        int lt = rule.indexOf('<');
        return lt > 0 ? rule.substring(0, lt) + " below " + rule.substring(lt + 1) : rule;
    }

    static String ms(Duration d) {
        long nanos = d.toNanos();
        if (nanos < 1_000_000) {
            return (nanos / 1_000) + " µs";
        }
        if (nanos < 1_000_000_000L) {
            return String.format("%.1f ms", nanos / 1_000_000.0);
        }
        return String.format("%.1f s", nanos / 1_000_000_000.0);
    }
}
