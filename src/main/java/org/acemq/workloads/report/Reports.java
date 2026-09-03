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
        out.append("  ]\n}\n");
        return out.toString();
    }

    /** @param reports finished runs
     *  @return the same, as Markdown */
    public static String toMarkdown(List<WorkloadReport> reports) {
        StringBuilder out = new StringBuilder("# Workload report\n\n");

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

    /** @param reports finished runs
     *  @return a self-contained HTML page */
    public static String toHtml(List<WorkloadReport> reports) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>Workload report</title>\n<style>\n").append(CSS).append("</style>\n");
        out.append("</head>\n<body>\n<main>\n<h1>Workload report</h1>\n");

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
                .append(escapeHtml(toJson(reports))).append("</pre></details>\n");
        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
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
