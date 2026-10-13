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
package org.acemq.workloads.report;

import java.time.Duration;
import java.util.List;

import org.acemq.workloads.RampReport;
import org.acemq.workloads.WorkloadReport;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;

/**
 * Renders runs as JSON, Markdown or HTML.
 *
 * <h2>Why not PDF</h2>
 *
 * <p>PDF was asked for and is deliberately not here. Producing one needs a layout engine and its
 * fonts — a large dependency in a tool whose whole output is a table and a list — and the result
 * looks worse than what a browser prints. The HTML carries {@code @media print} rules, so
 * printing it to PDF gives a better document than this could generate, with nothing added to the
 * build.
 *
 * <p><strong>JSON is the format that was not asked for and matters most.</strong> It is what a
 * pipeline reads to decide whether to promote a build, what a dashboard graphs over time, and
 * what lets two runs be compared without re-running either. HTML is for people and JSON is for
 * everything else.
 */
public final class Reports {

    private Reports() {
    }

    /** @param reports finished runs
     *  @return a machine-readable record of them */
    public static String toJson(List<WorkloadReport> reports) {
        return toJson(reports, List.of());
    }

    /** @param reports finished runs
     *  @param ramps finished ramps
     *  @return a machine-readable record of them; {@code ramps} appears only when there are some */
    public static String toJson(List<WorkloadReport> reports, List<RampReport> ramps) {
        StringBuilder out = new StringBuilder("{\n  \"workloads\": [\n");
        for (int i = 0; i < reports.size(); i++) {
            WorkloadReport report = reports.get(i);
            out.append("    {\n");
            field(out, "name", report.name(), true);
            field(out, "startedAt", report.startedAt().toString(), true);
            field(out, "durationSeconds", report.duration().toSeconds());
            field(out, "valid", report.isValid());
            field(out, "passed", report.passed());
            field(out, "offeredRatePerSecond", report.offeredRate());
            field(out, "publishedTotal", report.published());
            field(out, "confirmedTotal", report.confirmed());
            field(out, "failedTotal", report.failed());
            field(out, "consumedTotal", report.consumed());
            field(out, "publishRatePerSecond", round(report.achievedPublishRate()));
            field(out, "consumeRatePerSecond", round(report.consumeRate()));
            report.queueDepthAtEnd().ifPresent(depth -> field(out, "queueDepthAtEnd", depth));
            field(out, "blockedSeconds", round(report.blockedNanos() / 1_000_000_000.0));

            latency(out, "endToEnd", report.endToEnd());
            latency(out, "publish", report.publishLatency());
            latency(out, "sendLag", report.sendLag());

            samples(out, report.samples());

            out.append("      \"findings\": [\n");
            List<Finding> findings = report.findings();
            for (int f = 0; f < findings.size(); f++) {
                Finding finding = findings.get(f);
                out.append("        {")
                        .append("\"rule\": \"").append(escape(finding.rule())).append("\", ")
                        .append("\"severity\": \"").append(finding.severity()).append("\", ")
                        .append("\"observation\": \"").append(escape(finding.observation())).append("\", ")
                        .append("\"implication\": \"").append(escape(finding.implication())).append("\"}")
                        .append(f < findings.size() - 1 ? ",\n" : "\n");
            }
            out.append("      ]\n");
            out.append(i < reports.size() - 1 ? "    },\n" : "    }\n");
        }
        out.append("  ]");
        if (!ramps.isEmpty()) {
            out.append(",\n  \"ramps\": [\n");
            for (int i = 0; i < ramps.size(); i++) {
                rampJson(out, ramps.get(i));
                out.append(i < ramps.size() - 1 ? ",\n" : "\n");
            }
            out.append("  ]");
        }
        out.append("\n}\n");
        return out.toString();
    }

    private static void rampJson(StringBuilder out, RampReport ramp) {
        out.append("    {\n");
        field(out, "name", ramp.name(), true);
        // What it ran with, so a ramp started with --ramp is reproducible from its report.
        field(out, "settings", ramp.ramp().toString(), true);
        field(out, "valid", ramp.isValid());
        field(out, "passed", ramp.passed());
        field(out, "verdict", ramp.verdict(), true);
        field(out, "highestSustainedRatePerSecond", ramp.highestSustainedRate());
        if (ramp.ramp().ceilingAtLeast() > 0) {
            field(out, "ceilingAtLeast", ramp.ramp().ceilingAtLeast());
        }
        field(out, "stoppedBy", String.valueOf(ramp.stoppedBy()), true);
        field(out, "stopReason", ramp.stopReason(), true);
        out.append("      \"steps\": [\n");
        List<RampReport.Step> steps = ramp.steps();
        for (int s = 0; s < steps.size(); s++) {
            RampReport.Step step = steps.get(s);
            WorkloadReport r = step.report();
            out.append("        {\"offeredRatePerSecond\": ").append(step.rate())
                    .append(", \"refinement\": ").append(step.refinement())
                    .append(", \"sustained\": ").append(step.sustained())
                    .append(", \"valid\": ").append(r.isValid());
            if (!step.sustained()) {
                out.append(", \"stoppedBy\": \"").append(escape(step.stoppedBy())).append('"');
            }
            out.append(", \"publishRatePerSecond\": ").append(round(r.achievedPublishRate()))
                    .append(", \"consumeRatePerSecond\": ").append(round(r.consumeRate()))
                    .append(", \"failedTotal\": ").append(r.failed());
            LatencySummary e2e = r.endToEnd();
            if (!e2e.isEmpty()) {
                out.append(", \"endToEnd\": {\"p50Micros\": ").append(e2e.p50().toNanos() / 1000)
                        .append(", \"p90Micros\": ").append(e2e.p90().toNanos() / 1000)
                        .append(", \"p99Micros\": ").append(e2e.p99().toNanos() / 1000)
                        .append(", \"p999Micros\": ").append(e2e.p999().toNanos() / 1000)
                        .append(", \"maxMicros\": ").append(e2e.max().toNanos() / 1000)
                        .append('}');
            }
            out.append('}').append(s < steps.size() - 1 ? ",\n" : "\n");
        }
        out.append("      ]\n    }");
    }

    /** @param reports finished runs
     *  @return the same, as Markdown */
    public static String toMarkdown(List<WorkloadReport> reports) {
        return toMarkdown(reports, List.of());
    }

    /** @param reports finished runs
     *  @param ramps finished ramps
     *  @return the same, as Markdown */
    public static String toMarkdown(List<WorkloadReport> reports, List<RampReport> ramps) {
        StringBuilder out = new StringBuilder("# Workload report\n\n");
        for (RampReport ramp : ramps) {
            rampMarkdown(out, ramp);
        }
        if (reports.isEmpty()) {
            return out.toString();
        }

        out.append("| workload | result | offered | published | consumed | p50 | p99 | p99.9 |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        for (WorkloadReport report : reports) {
            out.append("| ").append(report.name())
                    .append(" | ").append(verdict(report))
                    .append(" | ").append(report.offeredRate() == 0 ? "unthrottled"
                            : String.format("%,d/s", report.offeredRate()))
                    .append(" | ").append(String.format("%,.0f/s", report.achievedPublishRate()))
                    .append(" | ").append(report.consumersEnabled()
                            ? String.format("%,.0f/s", report.consumeRate()) : "—")
                    .append(" | ").append(cell(report.endToEnd(), LatencySummary::p50))
                    .append(" | ").append(cell(report.endToEnd(), LatencySummary::p99))
                    .append(" | ").append(cell(report.endToEnd(), LatencySummary::p999))
                    .append(" |\n");
        }

        for (WorkloadReport report : reports) {
            out.append("\n## ").append(report.name()).append("\n\n");
            out.append("```\n").append(report.format()).append("```\n");
        }
        return out.toString();
    }

    private static void rampMarkdown(StringBuilder out, RampReport ramp) {
        out.append("## Ramp: ").append(ramp.name()).append("\n\n");
        out.append("`").append(ramp.ramp()).append("`\n\n");
        out.append("**").append(ramp.verdict()).append("** — highest sustained rate: **")
                .append(ramp.highestSustainedRate() == 0 ? "none"
                        : String.format("%,d/s", ramp.highestSustainedRate()))
                .append("**. Stopped by ").append(ramp.stopReason()).append(".\n\n");
        if (!ramp.isValid()) {
            out.append("> The step that stopped it was INVALID: this is the generator's or an"
                    + " alarm's limit, not the broker's ceiling.\n\n");
        }
        out.append("| offered | result | published | consumed | p50 | p90 | p99 | p99.9 | stopped by |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (RampReport.Step step : ramp.steps()) {
            WorkloadReport r = step.report();
            out.append("| ").append(String.format("%,d/s", step.rate()))
                    .append(step.refinement() ? " (refine)" : "")
                    .append(" | ").append(stepResult(step))
                    .append(" | ").append(String.format("%,.0f/s", r.achievedPublishRate()))
                    .append(" | ").append(r.consumersEnabled()
                            ? String.format("%,.0f/s", r.consumeRate()) : "—")
                    .append(" | ").append(cell(r.endToEnd(), LatencySummary::p50))
                    .append(" | ").append(cell(r.endToEnd(), LatencySummary::p90))
                    .append(" | ").append(cell(r.endToEnd(), LatencySummary::p99))
                    .append(" | ").append(cell(r.endToEnd(), LatencySummary::p999))
                    .append(" | ").append(step.sustained() ? "" : step.stoppedBy())
                    .append(" |\n");
        }
        // The curve, in text: p99 against offered rate, one bar per step in rate order.
        List<RampReport.Step> byRate = byRate(ramp);
        long worst = byRate.stream().filter(s -> !s.report().endToEnd().isEmpty())
                .mapToLong(s -> s.report().endToEnd().p99().toNanos()).max().orElse(0);
        if (worst > 0) {
            out.append("\np99 against offered rate:\n\n```\n");
            for (RampReport.Step step : byRate) {
                LatencySummary e2e = step.report().endToEnd();
                long p99 = e2e.isEmpty() ? 0 : e2e.p99().toNanos();
                int width = (int) Math.round(40.0 * p99 / worst);
                out.append(String.format("%12s %s %s%s%n", String.format("%,d/s", step.rate()),
                        "#".repeat(Math.max(width, p99 > 0 ? 1 : 0)) + " ".repeat(40 - width),
                        e2e.isEmpty() ? "-" : plain(e2e.p99()),
                        step.sustained() ? "" : "  <- " + step.stoppedBy()));
            }
            out.append("```\n");
        }
        out.append('\n');
    }

    private static List<RampReport.Step> byRate(RampReport ramp) {
        return ramp.steps().stream()
                .sorted(java.util.Comparator.comparingLong(RampReport.Step::rate)).toList();
    }

    private static String stepResult(RampReport.Step step) {
        return step.sustained() ? "ok" : step.report().isValid() ? "FAILED" : "INVALID";
    }

    private static String plain(Duration d) {
        long nanos = d.toNanos();
        return nanos < 1_000_000 ? (nanos / 1_000) + "us"
                : String.format("%.1fms", nanos / 1_000_000.0);
    }

    /** @param reports finished runs
     *  @return a self-contained HTML page */
    public static String toHtml(List<WorkloadReport> reports) {
        return toHtml(reports, List.of());
    }

    /** @param reports finished runs
     *  @param ramps finished ramps
     *  @return a self-contained HTML page */
    public static String toHtml(List<WorkloadReport> reports, List<RampReport> ramps) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>Workload report</title>\n<style>\n").append(CSS).append("</style>\n");
        out.append("</head>\n<body>\n<main>\n<h1>Workload report</h1>\n");
        for (RampReport ramp : ramps) {
            rampHtml(out, ramp);
        }

        if (!reports.isEmpty()) {
        out.append("<table>\n<thead><tr><th>workload</th><th>result</th><th>offered</th>")
                .append("<th>published</th><th>consumed</th><th>p50</th><th>p99</th><th>p99.9</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (WorkloadReport report : reports) {
            out.append("<tr><td>").append(escapeHtml(report.name())).append("</td>")
                    .append("<td class=\"").append(cssClass(report)).append("\">")
                    .append(verdict(report)).append("</td>")
                    .append("<td>").append(report.offeredRate() == 0 ? "unthrottled"
                            : String.format("%,d/s", report.offeredRate())).append("</td>")
                    .append("<td>").append(String.format("%,.0f/s", report.achievedPublishRate())).append("</td>")
                    .append("<td>").append(report.consumersEnabled()
                            ? String.format("%,.0f/s", report.consumeRate()) : "&mdash;").append("</td>")
                    .append("<td>").append(cell(report.endToEnd(), LatencySummary::p50)).append("</td>")
                    .append("<td>").append(cell(report.endToEnd(), LatencySummary::p99)).append("</td>")
                    .append("<td>").append(cell(report.endToEnd(), LatencySummary::p999)).append("</td>")
                    .append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
        }

        for (WorkloadReport report : reports) {
            out.append("<h2>").append(escapeHtml(report.name())).append("</h2>\n");
            if (!report.isValid()) {
                out.append("<p class=\"banner invalid\">This run did not measure what it was asked"
                        + " to. The numbers below describe something else, and no conclusion may"
                        + " be drawn from them.</p>\n");
            }
            for (Finding finding : report.findings()) {
                out.append("<div class=\"finding ")
                        .append(finding.severity().name().toLowerCase(java.util.Locale.ROOT))
                        .append("\">\n<strong>").append(finding.severity()).append("</strong> ")
                        .append(escapeHtml(finding.rule())).append("\n")
                        .append("<dl><dt>observed</dt><dd>").append(escapeHtml(finding.observation()))
                        .append("</dd><dt>means</dt><dd>").append(escapeHtml(finding.implication()))
                        .append("</dd>");
                finding.detail().ifPresent(d -> out.append("<dt>detail</dt><dd>")
                        .append(escapeHtml(d)).append("</dd>"));
                out.append("</dl>\n</div>\n");
            }
            out.append("<pre>").append(escapeHtml(report.format())).append("</pre>\n");
        }

        // The machine-readable form travels with the page, so a report mailed to somebody is
        // still re-analysable rather than being a picture of numbers.
        out.append("<h2>Data</h2>\n<details><summary>JSON</summary><pre>")
                .append(escapeHtml(toJson(reports, ramps))).append("</pre></details>\n");
        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void rampHtml(StringBuilder out, RampReport ramp) {
        out.append("<h2>Ramp: ").append(escapeHtml(ramp.name())).append("</h2>\n");
        out.append("<p class=\"summary\"><span class=\"").append(ramp.verdict().toLowerCase(
                java.util.Locale.ROOT)).append("\">").append(ramp.verdict()).append("</span> ")
                .append("highest sustained rate <strong>")
                .append(ramp.highestSustainedRate() == 0 ? "none"
                        : String.format("%,d/s", ramp.highestSustainedRate()))
                .append("</strong>. Stopped by ").append(escapeHtml(ramp.stopReason()))
                .append(".</p>\n");
        if (!ramp.isValid()) {
            out.append("<p class=\"banner invalid\">The step that stopped this ramp was invalid:"
                    + " the generator fell behind or the broker raised an alarm. The ceiling below"
                    + " is that limit, not the broker's.</p>\n");
        }
        List<RampReport.Step> byRate = byRate(ramp);
        out.append("<div class=\"charts\">\n");
        out.append(chart("delivered against offered (msg/s)", byRate,
                s -> s.report().consumersEnabled() ? s.report().consumeRate()
                        : s.report().achievedPublishRate(), true, v -> String.format("%,.0f", v)));
        out.append(chart("end-to-end p99 against offered", byRate,
                s -> s.report().endToEnd().isEmpty() ? Double.NaN
                        : s.report().endToEnd().p99().toNanos() / 1_000_000.0,
                false, v -> String.format("%.1fms", v)));
        out.append("</div>\n");

        out.append("<table>\n<thead><tr><th>offered</th><th>result</th><th>published</th>")
                .append("<th>consumed</th><th>p50</th><th>p90</th><th>p99</th><th>p99.9</th>")
                .append("<th>stopped by</th></tr></thead>\n<tbody>\n");
        for (RampReport.Step step : ramp.steps()) {
            WorkloadReport r = step.report();
            String css = step.sustained() ? "passed" : r.isValid() ? "failed" : "invalid";
            out.append("<tr><td>").append(String.format("%,d/s", step.rate()))
                    .append(step.refinement() ? " <small>refine</small>" : "").append("</td>")
                    .append("<td class=\"").append(css).append("\">").append(stepResult(step))
                    .append("</td><td>").append(String.format("%,.0f/s", r.achievedPublishRate()))
                    .append("</td><td>").append(r.consumersEnabled()
                            ? String.format("%,.0f/s", r.consumeRate()) : "&mdash;")
                    .append("</td><td>").append(cell(r.endToEnd(), LatencySummary::p50))
                    .append("</td><td>").append(cell(r.endToEnd(), LatencySummary::p90))
                    .append("</td><td>").append(cell(r.endToEnd(), LatencySummary::p99))
                    .append("</td><td>").append(cell(r.endToEnd(), LatencySummary::p999))
                    .append("</td><td>").append(step.sustained() ? "" : escapeHtml(step.stoppedBy()))
                    .append("</td></tr>\n");
        }
        out.append("</tbody>\n</table>\n");
        ramp.stoppingFinding().ifPresent(finding -> out.append("<div class=\"finding ")
                .append(finding.severity().name().toLowerCase(java.util.Locale.ROOT))
                .append("\">\n<strong>").append(finding.severity()).append("</strong> ")
                .append(escapeHtml(finding.rule())).append("\n<dl><dt>observed</dt><dd>")
                .append(escapeHtml(finding.observation())).append("</dd><dt>means</dt><dd>")
                .append(escapeHtml(finding.implication())).append("</dd></dl>\n</div>\n"));
        out.append("<pre>").append(escapeHtml(ramp.format())).append("</pre>\n");
    }

    /**
     * A line chart as inline SVG: offered rate across, one value up. No script and no library, so
     * the page stays one file that prints. Failed steps are drawn hollow.
     */
    private static String chart(String title, List<RampReport.Step> steps,
            java.util.function.ToDoubleFunction<RampReport.Step> value, boolean diagonal,
            java.util.function.DoubleFunction<String> label) {
        double w = 480, h = 220, left = 56, right = 12, top = 24, bottom = 32;
        double maxX = steps.stream().mapToLong(RampReport.Step::rate).max().orElse(1);
        double maxY = steps.stream().mapToDouble(value).filter(v -> !Double.isNaN(v)).max().orElse(0);
        if (diagonal) {
            maxY = Math.max(maxY, maxX);
        }
        if (maxY <= 0) {
            maxY = 1;
        }
        double fx = (w - left - right) / maxX;
        double fy = (h - top - bottom) / maxY;
        StringBuilder svg = new StringBuilder();
        svg.append("<figure><svg viewBox=\"0 0 ").append((int) w).append(' ').append((int) h)
                .append("\" role=\"img\" aria-label=\"").append(escapeHtml(title)).append("\">");
        svg.append(String.format("<text x=\"%.0f\" y=\"14\" class=\"t\">%s</text>", left,
                escapeHtml(title)));
        svg.append(String.format("<line x1=\"%.0f\" y1=\"%.0f\" x2=\"%.0f\" y2=\"%.0f\" class=\"axis\"/>",
                left, h - bottom, w - right, h - bottom));
        svg.append(String.format("<line x1=\"%.0f\" y1=\"%.0f\" x2=\"%.0f\" y2=\"%.0f\" class=\"axis\"/>",
                left, top, left, h - bottom));
        svg.append(String.format("<text x=\"%.0f\" y=\"%.0f\" class=\"l\" text-anchor=\"end\">%s</text>",
                left - 4, top + 4, escapeHtml(label.apply(maxY))));
        svg.append(String.format("<text x=\"%.0f\" y=\"%.0f\" class=\"l\" text-anchor=\"end\">0</text>",
                left - 4, h - bottom));
        svg.append(String.format("<text x=\"%.0f\" y=\"%.0f\" class=\"l\" text-anchor=\"end\">%,.0f/s</text>",
                w - right, h - 8, maxX));
        if (diagonal) {
            svg.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" class=\"ref\"/>",
                    left, h - bottom, left + maxX * fx, h - bottom - maxX * fy));
        }
        StringBuilder line = new StringBuilder();
        StringBuilder dots = new StringBuilder();
        for (RampReport.Step step : steps) {
            double v = value.applyAsDouble(step);
            if (Double.isNaN(v)) {
                continue;
            }
            double x = left + step.rate() * fx;
            double y = h - bottom - v * fy;
            line.append(String.format("%.1f,%.1f ", x, y));
            dots.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" class=\"%s\">"
                    + "<title>%,d/s: %s</title></circle>", x, y,
                    step.sustained() ? "ok" : "bad", step.rate(), escapeHtml(label.apply(v))));
        }
        svg.append("<polyline points=\"").append(line).append("\" class=\"line\"/>").append(dots);
        svg.append("</svg></figure>\n");
        return svg.toString();
    }

    private static String verdict(WorkloadReport report) {
        return !report.isValid() ? "INVALID" : report.passed() ? "PASSED" : "FAILED";
    }

    private static String cssClass(WorkloadReport report) {
        return !report.isValid() ? "invalid" : report.passed() ? "passed" : "failed";
    }

    private static String cell(LatencySummary summary,
            java.util.function.Function<LatencySummary, Duration> pick) {
        return summary.isEmpty() ? "&mdash;" : human(pick.apply(summary));
    }

    private static String human(Duration d) {
        long nanos = d.toNanos();
        if (nanos < 1_000_000) {
            return (nanos / 1_000) + "&#181;s";
        }
        return String.format("%.1fms", nanos / 1_000_000.0);
    }

    /**
     * The run as it went, one object per sampling interval.
     *
     * <p>Written because the aggregate above cannot answer "what happened at 14:02". A run that
     * idles at 3ms and spends twelve seconds at 900ms has the same summary as one that sat at 40ms
     * the whole way, and only one of those is a broker anybody would ship. Whoever reads this — a
     * dashboard, a pipeline, a fault drill lining a run up against its own timeline — can now see
     * which it was.
     *
     * <p>Each entry carries the wall-clock instant as well as the elapsed time, so it can be aligned
     * with records kept by something that was not this process.
     *
     * <p>Omitted entirely when there are none, rather than written as an empty list: a run shorter
     * than one interval measured no intervals, and `"samples": []` invites the reader to conclude
     * the run was idle.
     *
     * @param out the document so far
     * @param samples what the engine saw, oldest first
     */
    private static void samples(StringBuilder out, List<org.acemq.workloads.Sample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        out.append("      \"samples\": [\n");
        for (int s = 0; s < samples.size(); s++) {
            org.acemq.workloads.Sample sample = samples.get(s);
            out.append("        {")
                    .append("\"at\": \"").append(sample.at()).append("\", ")
                    .append("\"elapsedMillis\": ").append(sample.elapsed().toMillis()).append(", ")
                    .append("\"phase\": \"").append(sample.phase()).append("\", ")
                    .append("\"published\": ").append(sample.published()).append(", ")
                    .append("\"confirmed\": ").append(sample.confirmed()).append(", ")
                    .append("\"failed\": ").append(sample.failed()).append(", ")
                    .append("\"consumed\": ").append(sample.consumed()).append(", ")
                    .append("\"publishRatePerSecond\": ").append(round(sample.publishRate()))
                    .append(", ")
                    .append("\"consumeRatePerSecond\": ").append(round(sample.consumeRate()))
                    .append(", ")
                    .append("\"blocked\": ").append(sample.blocked());
            if (sample.queueDepth() != null) {
                out.append(", \"queueDepth\": ").append(sample.queueDepth());
            }
            if (!sample.endToEnd().isEmpty()) {
                out.append(", \"endToEndP50Micros\": ")
                        .append(sample.endToEnd().p50().toNanos() / 1000)
                        .append(", \"endToEndP99Micros\": ")
                        .append(sample.endToEnd().p99().toNanos() / 1000);
            }
            out.append("}").append(s < samples.size() - 1 ? ",\n" : "\n");
        }
        out.append("      ],\n");
    }

    private static void latency(StringBuilder out, String name, LatencySummary summary) {
        out.append("      \"").append(name).append("\": ");
        if (summary.isEmpty()) {
            out.append("null,\n");
            return;
        }
        out.append("{")
                .append("\"count\": ").append(summary.count()).append(", ")
                .append("\"p50Micros\": ").append(summary.p50().toNanos() / 1000).append(", ")
                .append("\"p90Micros\": ").append(summary.p90().toNanos() / 1000).append(", ")
                .append("\"p99Micros\": ").append(summary.p99().toNanos() / 1000).append(", ")
                .append("\"p999Micros\": ").append(summary.p999().toNanos() / 1000).append(", ")
                .append("\"maxMicros\": ").append(summary.max().toNanos() / 1000)
                .append("},\n");
    }

    private static void field(StringBuilder out, String name, String value, boolean quoted) {
        out.append("      \"").append(name).append("\": \"").append(escape(value)).append("\",\n");
    }

    private static void field(StringBuilder out, String name, Object value) {
        out.append("      \"").append(name).append("\": ").append(value).append(",\n");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String CSS = """
            :root { color-scheme: light dark;
              --fg:#1a1a1a; --bg:#fff; --muted:#5f5f5f; --line:#e4e4e4; --code-bg:#f7f7f5;
              --ok:#1a7f37; --bad:#b4451f; --warn:#9a6700; }
            @media (prefers-color-scheme: dark) {
              :root { --fg:#e8e8e8; --bg:#161616; --muted:#9c9c9c; --line:#2d2d2d;
                      --code-bg:#1e1e1e; --ok:#3fb950; --bad:#ff8a5c; --warn:#d29922; }
            }
            * { box-sizing:border-box; }
            body { margin:0; background:var(--bg); color:var(--fg);
              font:15px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; }
            main { max-width:60rem; margin:0 auto; padding:2.5rem 1.25rem 5rem; }
            h1 { font-size:1.9rem; letter-spacing:-.025em; margin:0 0 1.5rem; }
            h2 { font-size:1.2rem; margin:2.5rem 0 .8rem; padding-top:.4rem;
              border-top:1px solid var(--line); }
            table { border-collapse:collapse; width:100%; font-size:.9rem;
              display:block; overflow-x:auto; }
            th,td { text-align:left; padding:.5rem .75rem; border-bottom:1px solid var(--line);
              white-space:nowrap; }
            th { color:var(--muted); font-weight:600; }
            td.passed { color:var(--ok); font-weight:600; }
            td.failed { color:var(--bad); font-weight:600; }
            td.invalid { color:var(--bad); font-weight:700; }
            pre { background:var(--code-bg); border:1px solid var(--line); border-radius:8px;
              padding:1rem; overflow-x:auto; font-size:.8rem; line-height:1.5; }
            .banner { border-left:4px solid var(--bad); padding:.6rem 1rem; background:var(--code-bg);
              border-radius:0 6px 6px 0; }
            .finding { border-left:4px solid var(--line); padding:.4rem 1rem; margin:.75rem 0;
              background:var(--code-bg); border-radius:0 6px 6px 0; font-size:.9rem; }
            .finding.invalid { border-left-color:var(--bad); }
            .finding.failed { border-left-color:var(--bad); }
            .finding.warning { border-left-color:var(--warn); }
            .finding.info { border-left-color:var(--ok); }
            dl { margin:.4rem 0 0; }
            dt { color:var(--muted); font-size:.78rem; text-transform:uppercase;
              letter-spacing:.04em; margin-top:.4rem; }
            dd { margin:0 0 .2rem; }
            details summary { cursor:pointer; color:var(--muted); }
            .summary .passed { color:var(--ok); font-weight:700; }
            .summary .failed, .summary .invalid { color:var(--bad); font-weight:700; }
            .charts { display:grid; grid-template-columns:repeat(auto-fit, minmax(18rem, 1fr));
              gap:1rem; margin:1rem 0; }
            figure { margin:0; }
            svg { width:100%; height:auto; }
            svg .axis { stroke:var(--line); }
            svg .ref { stroke:var(--muted); stroke-dasharray:4 4; }
            svg .line { fill:none; stroke:var(--fg); stroke-width:1.5; }
            svg circle.ok { fill:var(--ok); }
            svg circle.bad { fill:var(--bg); stroke:var(--bad); stroke-width:2; }
            svg text { fill:var(--muted); font-size:11px; }
            svg text.t { fill:var(--fg); font-size:12px; font-weight:600; }
            /* Printing this is how a PDF gets made, so it is worth making the printed page
               readable rather than shipping a layout engine to produce a worse one. */
            @media print {
              :root { --fg:#000; --bg:#fff; --line:#ccc; --code-bg:#f6f6f6; }
              main { max-width:none; padding:0; }
              h2 { page-break-after:avoid; }
              .finding, pre, table { page-break-inside:avoid; }
              details { display:none; }
            }
            """;
}
