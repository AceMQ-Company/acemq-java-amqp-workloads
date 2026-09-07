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
package org.acemq.workloads.studio.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.scenario.ScenarioSample;
import org.acemq.workloads.scenario.ScenarioFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every run, and every reading taken during it.
 *
 * <p>Keeping the readings is what makes a finished run worth revisiting: it can be drawn again
 * exactly as it was watched, and two runs can be put side by side without running either of them
 * a second time. A run is a few hundred rows, which is nothing, and re-running a load test to
 * answer "was it better before" is not nothing.
 */
@Repository
public class RunStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RunStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * A run, as the history shows it.
     *
     * @param id the run
     * @param scenarioId the scenario it came from, when it was a saved one
     * @param scenarioName what it was called
     * @param broker where it ran
     * @param startedAt when it began
     * @param finishedAt when it ended, or null while it is going
     * @param status running, finished or failed
     * @param verdict passed, failed or invalid, once there is a report
     * @param error what went wrong, when something did
     */
    public record Summary(String id, String scenarioId, String scenarioName, String broker,
            Instant startedAt, Instant finishedAt, String status, String verdict, String error) {
    }

    /**
     * Records a run that has just started.
     *
     * @param id the run
     * @param scenarioId the saved scenario, or null
     * @param scenario what is being run
     * @param broker where
     */
    public void started(String id, String scenarioId, ScenarioFile scenario, String broker) {
        jdbc.update("""
                INSERT INTO runs (id, scenario_id, scenario_name, scenario_json, broker,
                                  started_at, status)
                VALUES (?, ?, ?, ?, ?, ?, 'running')""",
                id, scenarioId, scenario.name(), write(scenario), redact(broker),
                Instant.now().toString());
    }

    /**
     * @param runId the run
     * @param sample a reading
     */
    public void sample(String runId, ScenarioSample sample) {
        jdbc.update("""
                INSERT INTO samples (run_id, at, elapsed_ms, phase, json)
                VALUES (?, ?, ?, ?, ?)""",
                runId, sample.at().toString(), sample.elapsed().toMillis(),
                sample.phase().name(), write(sample));
    }

    /**
     * @param runId the run
     * @param verdict passed, failed or invalid
     * @param report the report, as JSON
     * @param html the same report as a page, for somebody to read or attach to a ticket
     * @param markdown the same report for a pull request or an issue
     */
    public void finished(String runId, String verdict, Object report, String html,
            String markdown) {
        jdbc.update("""
                UPDATE runs SET status = 'finished', finished_at = ?, verdict = ?,
                    report_json = ?, report_html = ?, report_md = ?
                WHERE id = ?""",
                Instant.now().toString(), verdict, write(report), html, markdown, runId);
    }

    /**
     * The report in the form somebody asked for it.
     *
     * @param runId the run
     * @param format {@code json}, {@code html} or {@code md}
     * @return it, if that run finished
     */
    public Optional<String> report(String runId, String format) {
        String column = switch (format) {
            case "html" -> "report_html";
            case "md", "markdown" -> "report_md";
            default -> "report_json";
        };
        return jdbc.query("SELECT " + column + " AS body FROM runs WHERE id = ?",
                        (rs, row) -> rs.getString("body"), runId)
                .stream().filter(body -> body != null).findFirst();
    }

    /**
     * @param runId the run
     * @param failure why there is no report
     */
    public void failed(String runId, Throwable failure) {
        jdbc.update("""
                UPDATE runs SET status = 'failed', finished_at = ?, error = ? WHERE id = ?""",
                Instant.now().toString(), because(failure), runId);
    }

    /**
     * The failure, and what actually caused it.
     *
     * <p>The top of the chain is usually the least useful sentence in it. "could not connect to
     * amqps://broker:5671" is what the transport says whether the certificate was refused, the
     * password was wrong or the port was closed — and the answer somebody needs is three causes
     * further down, where it says the certificate is marked development-only.
     *
     * @param failure what went wrong
     * @return the message, with the root cause when it says something different
     */
    static String because(Throwable failure) {
        String message = String.valueOf(failure.getMessage());

        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String root = String.valueOf(cause.getMessage());

        if (cause == failure || root.equals(message) || root.equals("null")) {
            return message;
        }
        return message + " -- " + root;
    }

    /**
     * @param limit how many
     * @return the most recent runs
     */
    public List<Summary> recent(int limit) {
        return jdbc.query("""
                SELECT id, scenario_id, scenario_name, broker, started_at, finished_at,
                       status, verdict, error
                FROM runs ORDER BY started_at DESC LIMIT ?""",
                (rs, row) -> new Summary(
                        rs.getString("id"),
                        rs.getString("scenario_id"),
                        rs.getString("scenario_name"),
                        rs.getString("broker"),
                        Instant.parse(rs.getString("started_at")),
                        rs.getString("finished_at") == null
                                ? null : Instant.parse(rs.getString("finished_at")),
                        rs.getString("status"),
                        rs.getString("verdict"),
                        rs.getString("error")),
                limit);
    }

    /**
     * @param runId a run
     * @return its report, as stored
     */
    public Optional<String> report(String runId) {
        return jdbc.query("SELECT report_json FROM runs WHERE id = ?",
                        (rs, row) -> rs.getString("report_json"), runId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * @param runId a run
     * @return every reading taken during it, in order
     */
    public List<String> samples(String runId) {
        return jdbc.query("SELECT json FROM samples WHERE run_id = ? ORDER BY elapsed_ms",
                (rs, row) -> rs.getString("json"), runId);
    }

    /**
     * The broker URL, with the password taken out.
     *
     * <p>A broker URL carries credentials, and the history is a file somebody will copy to
     * somebody else the first time they want a second opinion on a number. Storing what was
     * connected to is useful; storing the password is a leak waiting for a share.
     */
    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not store a run", e);
        }
    }
}
