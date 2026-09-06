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

import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.rules.Finding;
import org.acemq.workloads.scenario.Expect;
import org.acemq.workloads.scenario.ScenarioReport;

/**
 * A scenario's report as a file.
 *
 * <p>Separate from {@link Reports} because the shape is genuinely different: a workload report is
 * one row and a scenario report is a table of queues and a table of producers. Forcing both
 * through one writer would produce a format that suits neither.
 *
 * <p><strong>JSON is the one to care about.</strong> It is what a pipeline reads to decide whether
 * to promote a build, and what lets two runs be compared without re-running either. HTML is for
 * people, and embeds the JSON so a report mailed to somebody is still re-analysable rather than a
 * picture of numbers.
 */
public final class ScenarioReports {

    private ScenarioReports() {
    }

    /**
     * @param report a scenario report
     * @return it as JSON
     */
    public static String toJson(ScenarioReport report) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"scenario\": ").append(quote(report.scenario().name())).append(",\n");
        out.append("  \"description\": ").append(quote(report.scenario().description())).append(",\n");
        out.append("  \"startedAt\": ").append(quote(report.startedAt().toString())).append(",\n");
        out.append("  \"durationMs\": ").append(report.duration().toMillis()).append(",\n");
        out.append("  \"stoppedEarly\": ").append(report.wasStoppedEarly()).append(",\n");
        out.append("  \"verdict\": ").append(quote(verdict(report))).append(",\n");
        out.append("  \"valid\": ").append(report.isValid()).append(",\n");
        out.append("  \"totalPublished\": ").append(report.totalPublished()).append(",\n");
        out.append("  \"totalConsumed\": ").append(report.totalConsumed()).append(",\n");
        out.append("  \"blockedMs\": ").append(report.blockedFor().toMillis()).append(",\n");

        out.append("  \"producers\": [\n");
        for (int i = 0; i < report.producers().size(); i++) {
            ScenarioReport.ProducerResult producer = report.producers().get(i);
            out.append("    {")
                    .append("\"name\": ").append(quote(producer.name())).append(", ")
                    .append("\"offeredRate\": ").append(producer.offeredRate()).append(", ")
                    .append("\"achievedRate\": ")
                    .append(round(producer.achievedRate(report.duration()))).append(", ")
                    .append("\"published\": ").append(producer.published()).append(", ")
                    .append("\"confirmed\": ").append(producer.confirmed()).append(", ")
                    .append("\"failed\": ").append(producer.failed()).append(", ")
                    .append("\"publishLatency\": ").append(latency(producer.publishLatency())).append(", ")
                    .append("\"sendLag\": ").append(latency(producer.sendLag())).append(", ")
                    .append("\"expected\": ").append(expected(producer.expectations()))
                    .append('}').append(i < report.producers().size() - 1 ? ",\n" : "\n");
        }
        out.append("  ],\n");

        out.append("  \"queues\": [\n");
        for (int i = 0; i < report.queues().size(); i++) {
            ScenarioReport.QueueResult queue = report.queues().get(i);
            out.append("    {")
                    .append("\"name\": ").append(quote(queue.name())).append(", ")
                    .append("\"type\": ").append(quote(queue.type().wireName())).append(", ")
                    .append("\"consumers\": ").append(queue.consumers()).append(", ")
                    .append("\"consumed\": ").append(queue.consumed()).append(", ")
                    .append("\"consumeRate\": ")
                    .append(round(queue.consumeRate(report.duration()))).append(", ")
                    .append("\"endToEnd\": ").append(latency(queue.endToEnd())).append(", ")
                    .append("\"depthAtStart\": ").append(queue.depthAtStart()).append(", ")
                    .append("\"depthAtEnd\": ").append(queue.depthAtEnd()).append(", ")
                    .append("\"retainsMessages\": ").append(queue.retainsMessages()).append(", ")
                    .append("\"expected\": ").append(expected(queue.expectations()))
                    .append('}').append(i < report.queues().size() - 1 ? ",\n" : "\n");
        }
        out.append("  ],\n");

        out.append("  \"findings\": [\n");
        List<Finding> findings = report.findings();
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            out.append("    {")
                    .append("\"rule\": ").append(quote(finding.rule())).append(", ")
                    .append("\"severity\": ").append(quote(finding.severity().name())).append(", ")
                    .append("\"observation\": ").append(quote(finding.observation())).append(", ")
                    .append("\"implication\": ").append(quote(finding.implication()))
                    .append('}').append(i < findings.size() - 1 ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    /**
     * @param report a scenario report
     * @return it as Markdown, for a pull request comment or a ticket
     */
    public static String toMarkdown(ScenarioReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(report.scenario().name()).append("\n\n");
        if (!report.scenario().description().isBlank()) {
            out.append(report.scenario().description()).append("\n\n");
        }
        out.append("**").append(verdict(report).toUpperCase(java.util.Locale.ROOT)).append("** — ")
                .append(report.duration().toSeconds()).append("s measured, ")
                .append(String.format("%,d", report.totalPublished())).append(" published, ")
                .append(String.format("%,d", report.totalConsumed())).append(" consumed")
                .append(report.wasStoppedEarly() ? ", stopped early" : "").append("\n\n");

        out.append("## Queues\n\n");
        out.append("| queue | type | consumers | consumed/s | p50 | p99 | p99.9 | at the end |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        for (ScenarioReport.QueueResult queue : report.queues()) {
            out.append("| ").append(queue.name())
                    .append(" | ").append(queue.type().wireName())
                    .append(" | ").append(queue.consumers())
                    .append(" | ").append(String.format("%,.0f", queue.consumeRate(report.duration())))
                    .append(" | ").append(millis(queue.endToEnd(), LatencySummary::p50))
                    .append(" | ").append(millis(queue.endToEnd(), LatencySummary::p99))
                    .append(" | ").append(millis(queue.endToEnd(), LatencySummary::p999))
                    .append(" | ").append(depth(queue))
                    .append(" |\n");
        }

        out.append("\n## Producers\n\n");
        out.append("| producer | offered | achieved | failed | send lag p99 | confirm p99 |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (ScenarioReport.ProducerResult producer : report.producers()) {
            out.append("| ").append(producer.name())
                    .append(" | ").append(producer.offeredRate() == 0 ? "unthrottled"
                            : String.format("%,d/s", producer.offeredRate()))
                    .append(" | ").append(String.format("%,.0f/s",
                            producer.achievedRate(report.duration())))
                    .append(" | ").append(producer.failed())
                    .append(" | ").append(millis(producer.sendLag(), LatencySummary::p99))
                    .append(" | ").append(millis(producer.publishLatency(), LatencySummary::p99))
                    .append(" |\n");
        }

        if (!report.findings().isEmpty()) {
            out.append("\n## What was noticed\n\n");
            for (Finding finding : report.findings()) {
                out.append("**[").append(finding.severity()).append("] ")
                        .append(finding.rule()).append("**\n\n")
                        .append("- observed: ").append(finding.observation()).append('\n')
                        .append("- means: ").append(finding.implication()).append("\n\n");
            }
        }
        return out.toString();
    }

    /**
     * @param report a scenario report
     * @return it as a page somebody can read or print
     */
    public static String toHtml(ScenarioReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>").append(escape(report.scenario().name())).append("</title>\n");
        out.append("<style>\n").append(CSS).append("</style>\n</head>\n<body>\n<main>\n");

        out.append("<h1>").append(escape(report.scenario().name())).append("</h1>\n");
        if (!report.scenario().description().isBlank()) {
            out.append("<p class=\"lead\">").append(escape(report.scenario().description()))
                    .append("</p>\n");
        }

        out.append("<p class=\"verdict ").append(verdict(report)).append("\">")
                .append(verdict(report).toUpperCase(java.util.Locale.ROOT))
                .append("</p>\n<p class=\"summary\">")
                .append(report.duration().toSeconds()).append("s measured · ")
                .append(String.format("%,d", report.totalPublished())).append(" published · ")
                .append(String.format("%,d", report.totalConsumed())).append(" consumed")
                .append(report.wasStoppedEarly() ? " · stopped early" : "")
                .append("</p>\n");

        out.append("<h2>Queues</h2>\n<table>\n<thead><tr><th>queue</th><th>type</th>")
                .append("<th>consumers</th><th>consumed/s</th><th>p50</th><th>p99</th>")
                .append("<th>p99.9</th><th>at the end</th></tr></thead>\n<tbody>\n");
        for (ScenarioReport.QueueResult queue : report.queues()) {
            out.append("<tr><td>").append(escape(queue.name())).append("</td>")
                    .append("<td>").append(queue.type().wireName()).append("</td>")
                    .append("<td>").append(queue.consumers()).append("</td>")
                    .append("<td>").append(String.format("%,.0f",
                            queue.consumeRate(report.duration()))).append("</td>")
                    .append("<td>").append(millis(queue.endToEnd(), LatencySummary::p50)).append("</td>")
                    .append("<td>").append(millis(queue.endToEnd(), LatencySummary::p99)).append("</td>")
                    .append("<td>").append(millis(queue.endToEnd(), LatencySummary::p999)).append("</td>")
                    .append("<td>").append(escape(depth(queue))).append("</td></tr>\n");
        }
        out.append("</tbody>\n</table>\n");

        out.append("<h2>Producers</h2>\n<table>\n<thead><tr><th>producer</th><th>offered</th>")
                .append("<th>achieved</th><th>failed</th><th>send lag p99</th>")
                .append("<th>confirm p99</th></tr></thead>\n<tbody>\n");
        for (ScenarioReport.ProducerResult producer : report.producers()) {
            out.append("<tr><td>").append(escape(producer.name())).append("</td>")
                    .append("<td>").append(producer.offeredRate() == 0 ? "unthrottled"
                            : String.format("%,d/s", producer.offeredRate())).append("</td>")
                    .append("<td>").append(String.format("%,.0f/s",
                            producer.achievedRate(report.duration()))).append("</td>")
                    .append("<td>").append(producer.failed()).append("</td>")
                    .append("<td>").append(millis(producer.sendLag(), LatencySummary::p99)).append("</td>")
                    .append("<td>").append(millis(producer.publishLatency(), LatencySummary::p99))
                    .append("</td></tr>\n");
        }
        out.append("</tbody>\n</table>\n");

        if (!report.findings().isEmpty()) {
            out.append("<h2>What was noticed</h2>\n");
            for (Finding finding : report.findings()) {
                out.append("<div class=\"finding ").append(finding.severity().name().toLowerCase(
                        java.util.Locale.ROOT)).append("\">")
                        .append("<code>[").append(finding.severity()).append("] ")
                        .append(escape(finding.rule())).append("</code>")
                        .append("<p class=\"observed\">").append(escape(finding.observation()))
                        .append("</p><p>").append(escape(finding.implication()))
                        .append("</p></div>\n");
            }
        }

        // The numbers, embedded, so a report that was mailed to somebody is still something
        // they can analyse rather than a picture of a table.
        out.append("<script type=\"application/json\" id=\"report\">\n")
                .append(toJson(report).replace("</", "<\\/"))
                .append("</script>\n");

        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static String depth(ScenarioReport.QueueResult queue) {
        if (queue.depthAtEnd() == null) {
            return "—";
        }
        String count = String.format("%,d", queue.depthAtEnd());
        if (queue.retainsMessages()) {
            // A stream's depth is the length of its log, not a backlog: consumers do not remove
            // what they read. Printing it as "waiting" would read as a queue falling behind.
            return count + " retained";
        }
        return queue.grew() ? count + " waiting, still growing" : count + " waiting";
    }

    /**
     * What this node was asked for, so a report says what the verdict was measured against.
     *
     * <p>A report that only carries what happened cannot be read six months later: "p99 was 41ms"
     * is a number, and "p99 was 41ms against the 50ms this required" is an answer.
     *
     * @param expect the node's expectations, possibly none
     * @return the expectation as a JSON string, or null
     */
    private static String expected(Expect expect) {
        return expect == null || expect.isEmpty() ? "null" : quote(expect.toString());
    }

    private static String verdict(ScenarioReport report) {
        return !report.isValid() ? "invalid" : report.passed() ? "passed" : "failed";
    }

    private static String latency(LatencySummary summary) {
        if (summary == null || summary.count() == 0) {
            return "{\"count\": 0}";
        }
        return "{\"count\": " + summary.count()
                + ", \"p50Ms\": " + round(summary.p50().toNanos() / 1_000_000.0)
                + ", \"p99Ms\": " + round(summary.p99().toNanos() / 1_000_000.0)
                + ", \"p999Ms\": " + round(summary.p999().toNanos() / 1_000_000.0)
                + ", \"maxMs\": " + round(summary.max().toNanos() / 1_000_000.0) + "}";
    }

    private static String millis(LatencySummary summary,
            java.util.function.Function<LatencySummary, Duration> percentile) {
        if (summary == null || summary.count() == 0) {
            return "—";
        }
        return String.format("%.1fms", percentile.apply(summary).toNanos() / 1_000_000.0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root { color-scheme: light dark; }
            body { font: 15px/1.6 -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                   margin: 0; padding: 32px 20px; background: #fbfbfc; color: #16181d; }
            @media (prefers-color-scheme: dark) {
              body { background: #0e1013; color: #e7ebef; }
              table { border-color: #2f363d; }
              th, td { border-color: #21262d; }
              .finding { background: #121519; }
            }
            main { max-width: 1000px; margin: 0 auto; }
            h1 { font-size: 24px; margin: 0 0 6px; }
            h2 { font-size: 15px; text-transform: uppercase; letter-spacing: .06em;
                 color: #6b757f; margin: 30px 0 10px; }
            .lead { color: #6b757f; margin: 0 0 18px; }
            .verdict { display: inline-block; font-weight: 700; letter-spacing: .04em;
                       padding: 6px 14px; border-radius: 999px; margin: 0; }
            .verdict.passed { background: rgba(61,220,151,.18); color: #128a5b; }
            .verdict.failed { background: rgba(255,107,107,.18); color: #b02a2a; }
            .verdict.invalid { background: rgba(199,125,255,.2); color: #7827b8; }
            .summary { color: #6b757f; margin: 10px 0 0; }
            table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
            th { text-align: left; font-size: 11px; text-transform: uppercase;
                 letter-spacing: .06em; color: #6b757f; padding: 8px 10px;
                 border-bottom: 1px solid #d8dde3; }
            td { padding: 9px 10px; border-bottom: 1px solid #eceff3; }
            .finding { border-left: 3px solid #d8dde3; padding: 10px 14px; margin: 12px 0;
                       background: #fff; }
            .finding.invalid { border-color: #c77dff; }
            .finding.failed { border-color: #ff6b6b; }
            .finding.warning { border-color: #ffb457; }
            .finding code { font-size: 12px; color: #6b757f; }
            .finding p { margin: 4px 0 0; }
            .finding .observed { font-weight: 600; }
            @media print { body { background: #fff; } .verdict { border: 1px solid #999; } }
            """;
}
