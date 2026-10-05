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
package org.acemq.workloads.endurance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The soak's verdict, from its readings: medians of the baseline and final readings per client,
 * against the allowances, with known upstream breaches classified rather than failed.
 *
 * <p>A port of the verdict {@code scripts/soak.sh} computed in Python, sentence for sentence:
 * {@code release-preflight.sh} matches a client's name inside these sentences to decide whether
 * a failed soak blocks that client's release, so the wording is an interface.
 */
public final class Verdict {

    /** One client's before and after. */
    public record Row(String lang, long rssBefore, long rssAfter, long fdsBefore, long fdsAfter,
            long threadsBefore, long threadsAfter, List<String> notes) {

        /** @return "clean", or the notes joined */
        public String verdict() {
            return notes.isEmpty() ? "clean" : String.join(", ", notes);
        }

        String line() {
            return "| " + lang + " | " + mb(rssBefore) + " | " + mb(rssAfter) + " | " + fdsBefore
                    + " | " + fdsAfter + " | " + threadsBefore + " | " + threadsAfter + " | "
                    + verdict() + " |";
        }

        private static String mb(long kb) {
            return kb != 0 ? kb / 1024 + "MB" : "-";
        }
    }

    private final List<Row> rows;
    private final List<String> recovery;
    private final List<String> known;
    private final List<String> failures;
    private final long settledLow;
    private final long settledHigh;
    private final List<Long> afterClose;

    private Verdict(List<Row> rows, List<String> recovery, List<String> known,
            List<String> failures, long settledLow, long settledHigh, List<Long> afterClose) {
        this.rows = rows;
        this.recovery = recovery;
        this.known = known;
        this.failures = failures;
        this.settledLow = settledLow;
        this.settledHigh = settledHigh;
        this.afterClose = afterClose;
    }

    /**
     * @param readings every reading, in the order taken
     * @param afterClose the cluster's connection count right after each close, -1 where unread
     * @param closed how many forced recoveries the run did
     * @param allowances the growth allowed before it is a leak
     * @param knownUpstream breaches allowed within a ceiling of their own
     * @return the verdict
     */
    public static Verdict of(List<Reading> readings, List<Long> afterClose, int closed,
            EnduranceConfig.Allowances allowances, List<EnduranceConfig.KnownUpstream> knownUpstream) {
        Map<String, Map<String, List<Reading>>> byLang = new TreeMap<>();
        List<Long> connections = new ArrayList<>();
        for (Reading r : readings) {
            byLang.computeIfAbsent(r.lang(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.phase(), k -> new ArrayList<>()).add(r);
            connections.add(r.connections());
        }

        List<String> failures = new ArrayList<>();
        List<String> known = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Reading>>> e : byLang.entrySet()) {
            String lang = e.getKey();
            List<Reading> base = e.getValue().getOrDefault("baseline", List.of());
            List<Reading> fin = e.getValue().getOrDefault("final", List.of());
            long bRss = median(base, Reading::rssKb);
            long fRss = median(fin, Reading::rssKb);
            long bFds = median(base, Reading::fds);
            long fFds = median(fin, Reading::fds);
            long bThr = median(base, Reading::threads);
            long fThr = median(fin, Reading::threads);

            List<String> notes = new ArrayList<>();
            Classifier c = new Classifier(lang, closed, knownUpstream, known, failures);
            if (fRss == 0) {
                failures.add("the " + lang + " load was not running at the end of the soak");
                notes.add("process gone");
            } else {
                if (fFds > bFds + allowances.descriptors) {
                    notes.add(c.classify("descriptors", "descriptor growth", lang
                            + " descriptors grew from " + bFds + " to " + fFds + ", more than the "
                            + allowances.descriptors + " allowed", fFds, bFds));
                }
                if (fThr > bThr + allowances.threads) {
                    notes.add(c.classify("threads", "thread growth", lang + " threads grew from "
                            + bThr + " to " + fThr + ", more than the " + allowances.threads
                            + " allowed", fThr, bThr));
                }
                if (bRss > 0 && fRss > bRss * allowances.memoryFactor) {
                    notes.add(c.classify("memory", "memory growth", lang
                            + " resident memory grew from " + bRss / 1024 + "MB to " + fRss / 1024
                            + "MB, more than " + number(allowances.memoryFactor)
                            + "x the baseline", fRss, bRss));
                }
                // Cumulative counters, so the question is whether the last reading is ahead of
                // the first. A load that died leaks nothing, so this is part of the verdict.
                long firstPub = base.isEmpty() ? -1 : base.get(0).published();
                long lastPub = fin.isEmpty() ? -1 : fin.get(fin.size() - 1).published();
                long firstCon = base.isEmpty() ? -1 : base.get(0).consumed();
                long lastCon = fin.isEmpty() ? -1 : fin.get(fin.size() - 1).consumed();
                if (lastPub <= firstPub || lastCon <= firstCon) {
                    failures.add(lang + " stopped working during the soak: published " + firstPub
                            + "->" + lastPub + ", consumed " + firstCon + "->" + lastCon);
                    notes.add("stalled");
                }
            }
            rows.add(new Row(lang, bRss, fRss, bFds, fFds, bThr, fThr, notes));
        }

        // Did the cycles do anything? Clients are back before the timed readings land, so only
        // the count taken right after each close can say.
        List<String> recovery = new ArrayList<>();
        long low = connections.stream().mapToLong(Long::longValue).min().orElse(-1);
        long high = connections.stream().mapToLong(Long::longValue).max().orElse(-1);
        if (!connections.isEmpty()) {
            recovery.add("connections while settled: low " + low + ", high " + high);
        }
        if (!afterClose.isEmpty()) {
            long aLow = afterClose.stream().mapToLong(Long::longValue).min().getAsLong();
            long aHigh = afterClose.stream().mapToLong(Long::longValue).max().getAsLong();
            recovery.add("connections immediately after a close: low " + aLow + ", high " + aHigh
                    + ", over " + afterClose.size() + " cycles");
            if (!connections.isEmpty() && aLow >= high) {
                failures.add("closing connections never removed any: " + aLow + " still open"
                        + " right after a close against " + high + " when settled, so none of"
                        + " the " + closed + " cycles disturbed a client and nothing above was"
                        + " tested");
            }
        } else {
            failures.add("no cycle recorded a connection count, so nothing proves the clients"
                    + " ever recovered");
        }
        return new Verdict(rows, recovery, known, failures, low, high, List.copyOf(afterClose));
    }

    private record Classifier(String lang, int closed, List<EnduranceConfig.KnownUpstream> entries,
            List<String> known, List<String> failures) {

        String classify(String kind, String note, String message, long measured, long base) {
            for (EnduranceConfig.KnownUpstream k : entries) {
                if (lang.equals(k.client) && kind.equals(k.kind)) {
                    long ceiling = k.ceiling(base, closed);
                    if (measured <= ceiling) {
                        String allowed = "memory".equals(kind) ? ceiling / 1024 + "MB"
                                : String.valueOf(ceiling);
                        known.add(message + " — known, " + k.why + " (up to " + allowed
                                + " is allowed for it)");
                        return note + " (known)";
                    }
                }
            }
            failures.add(message);
            return note;
        }
    }

    // ponytail: an even count takes the lower-rounded mean where Python's median gave x.5; the
    // soak always takes an odd number of readings, so the two never differ in practice.
    private static long median(List<Reading> samples, java.util.function.ToLongFunction<Reading> f) {
        if (samples.isEmpty()) {
            return -1;
        }
        long[] v = samples.stream().mapToLong(f).sorted().toArray();
        int m = v.length / 2;
        return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
    }

    /** Python's {@code :g}: 2.0 is "2", 2.5 is "2.5". */
    static String number(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    public boolean passed() {
        return failures.isEmpty();
    }

    /** @return {@code PASSED} or {@code FAILED} */
    public String status() {
        return passed() ? "PASSED" : "FAILED";
    }

    public List<Row> rows() {
        return rows;
    }

    public List<String> known() {
        return known;
    }

    public List<String> failures() {
        return failures;
    }

    /** @return the markdown table, header included, without a trailing newline */
    public String table() {
        StringBuilder b = new StringBuilder();
        b.append("| library | RSS before | RSS after | fds before | fds after | threads before |"
                + " threads after | verdict |\n");
        b.append("| --- | --- | --- | --- | --- | --- | --- | --- |");
        for (Row r : rows) {
            b.append('\n').append(r.line());
        }
        return b.toString();
    }

    /** @return the connection-count lines, without a trailing newline */
    public String recovery() {
        return String.join("\n", recovery);
    }

    /**
     * The report, byte for byte in the format {@code scripts/soak.sh} wrote and
     * {@code release-preflight.sh} reads: a {@code **PASSED**} or {@code **FAILED**} line, the
     * reasons as {@code - } bullets under "## What failed", and allowed breaches under
     * "## Known upstream".
     */
    public String markdown(String stamp, int closed, int cycleSeconds, int sampleSeconds,
            EnduranceConfig.Allowances a, String readings) {
        StringBuilder b = new StringBuilder();
        b.append("# Soak — ").append(stamp).append("\n\n");
        b.append(closed).append(" cycles, one every ").append(cycleSeconds).append("s, sampling every ")
                .append(sampleSeconds).append("s. Every client lost every connection ")
                .append(closed).append(" times.\n\n");
        b.append("**").append(status()).append("**\n\n");
        b.append(table()).append("\n\n");
        b.append(recovery()).append("\n\n");
        if (!passed()) {
            b.append("## What failed\n\n");
            failures.forEach(f -> b.append("- ").append(f).append('\n'));
            b.append('\n');
        }
        if (!known.isEmpty()) {
            b.append("## Known upstream\n\n");
            b.append("Allowed because nothing here can fix it, each within a ceiling measured for\n");
            b.append("it. Growth past that ceiling is reported as a failure like anything else.\n\n");
            known.forEach(k -> b.append("- ").append(k).append('\n'));
            b.append('\n');
        }
        b.append("Allowances: descriptors +").append(a.descriptors).append(", threads +")
                .append(a.threads).append(", resident memory ").append(number(a.memoryFactor))
                .append("x the baseline.\n");
        b.append("Readings: `").append(readings).append("`\n");
        return b.toString();
    }

    /** @return what the script printed at the end, before the status line */
    public String console() {
        StringBuilder b = new StringBuilder("\n").append(table()).append("\n\n")
                .append(recovery()).append('\n');
        if (!known.isEmpty()) {
            b.append("\nknown upstream:\n");
            known.forEach(k -> b.append("  ").append(k).append('\n'));
        }
        if (!passed()) {
            b.append('\n');
            failures.forEach(f -> b.append("  ").append(f).append('\n'));
        }
        return b.toString();
    }

    /**
     * The JSON report the console reads: the same facts as the markdown, as data.
     *
     * @return a map ready for Jackson
     */
    public Map<String, Object> json(String stamp, int closed, EnduranceConfig config,
            String report, String readingsPath, List<Reading> readings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "framework");
        out.put("id", stamp);
        out.put("verdict", status());
        out.put("summary", closed + " cycles, one every " + config.cycleSeconds + "s, sampling every "
                + config.sampleSeconds + "s. Every client lost every connection " + closed + " times.");
        out.put("cycles", closed);
        out.put("cycleSeconds", config.cycleSeconds);
        out.put("sampleSeconds", config.sampleSeconds);
        out.put("warmupSeconds", config.warmupSeconds);
        out.put("cooldownSeconds", config.cooldownSeconds);
        out.put("settled", recovery.isEmpty() || !recovery.get(0).startsWith("connections while")
                ? null : recovery.get(0).substring("connections while settled: ".length()));
        out.put("settledLow", settledLow);
        out.put("settledHigh", settledHigh);
        out.put("afterClose", afterClose.isEmpty() ? null : recovery.get(recovery.size() - 1)
                .substring("connections immediately after a close: ".length()));
        out.put("afterCloseCounts", afterClose);
        EnduranceConfig.Allowances a = config.allowances;
        out.put("allowances", "descriptors +" + a.descriptors + ", threads +" + a.threads
                + ", resident memory " + number(a.memoryFactor) + "x the baseline.");
        out.put("fdSlack", a.descriptors);
        out.put("threadSlack", a.threads);
        out.put("rssFactor", a.memoryFactor);
        List<Map<String, Object>> clients = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("library", r.lang());
            c.put("rssBefore", Row.mb(r.rssBefore()));
            c.put("rssAfter", Row.mb(r.rssAfter()));
            c.put("fdsBefore", String.valueOf(r.fdsBefore()));
            c.put("fdsAfter", String.valueOf(r.fdsAfter()));
            c.put("threadsBefore", String.valueOf(r.threadsBefore()));
            c.put("threadsAfter", String.valueOf(r.threadsAfter()));
            c.put("verdict", r.verdict());
            c.put("rssBeforeKb", r.rssBefore());
            c.put("rssAfterKb", r.rssAfter());
            clients.add(c);
        }
        out.put("clients", clients);
        out.put("known", known);
        out.put("failures", failures);
        out.put("report", report);
        out.put("readings", readingsPath);
        out.put("series", series(readings));
        return out;
    }

    /**
     * Per client, every reading as {@code [minutes from the first reading, RSS in MB, fds,
     * threads]}: the shape the console's chart draws.
     */
    public static Map<String, List<double[]>> series(List<Reading> readings) {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        long first = readings.isEmpty() ? 0 : readings.get(0).epoch();
        for (Reading r : readings) {
            out.computeIfAbsent(r.lang(), k -> new ArrayList<>()).add(new double[] {
                round((r.epoch() - first) / 60.0), round(r.rssKb() / 1024.0), r.fds(), r.threads()});
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
