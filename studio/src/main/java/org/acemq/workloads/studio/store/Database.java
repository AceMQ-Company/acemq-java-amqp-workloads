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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import org.acemq.workloads.studio.StudioProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One SQLite file, created on first use.
 *
 * <p>No migration tool. The schema is small, the statements are {@code IF NOT EXISTS}, and adding
 * Flyway to a desktop-shaped application would be a dependency and a migration folder in exchange
 * for four tables. When that stops being true it will be worth the change; it is not true yet.
 *
 * <p>The scenario and the report are stored as JSON text rather than being spread across columns.
 * That JSON is already the file format — it is what a saved
 * {@code acemq-workload-<name>-<date>.json} contains and what the command line reads — so
 * normalising it here would mean maintaining two shapes of the same thing and translating between
 * them on every read.
 */
@Configuration
public class Database {

    /**
     * @param properties where the file lives
     * @return a data source for it
     */
    @Bean
    public DataSource dataSource(StudioProperties properties) {
        Path file = properties.databasePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot create " + file.getParent() + " for the studio's database", e);
        }

        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.sqlite.JDBC");
        // Busy timeout because the sampling writes while a request reads, and SQLite's default is
        // to fail immediately rather than wait.
        source.setUrl("jdbc:sqlite:" + file + "?busy_timeout=5000");
        return source;
    }

    /**
     * @param dataSource the file
     * @return a template with the schema in place
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);

        // Write-ahead logging, so a long-running write does not block reads. The studio writes a
        // row a second during a run and reads the history while it does.
        template.execute("PRAGMA journal_mode = WAL");
        template.execute("PRAGMA synchronous = NORMAL");

        template.execute("""
                CREATE TABLE IF NOT EXISTS scenarios (
                    id           TEXT PRIMARY KEY,
                    name         TEXT NOT NULL,
                    description  TEXT NOT NULL DEFAULT '',
                    json         TEXT NOT NULL,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL
                )""");

        template.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                    id            TEXT PRIMARY KEY,
                    scenario_id   TEXT,
                    scenario_name TEXT NOT NULL,
                    scenario_json TEXT NOT NULL,
                    broker        TEXT NOT NULL,
                    started_at    TEXT NOT NULL,
                    finished_at   TEXT,
                    status        TEXT NOT NULL,
                    verdict       TEXT,
                    report_json   TEXT,
                    error         TEXT
                )""");

        // Every reading, so a finished run can be drawn again exactly as it was watched. A run is
        // a few hundred rows; keeping them is what makes two runs comparable afterwards.
        template.execute("""
                CREATE TABLE IF NOT EXISTS samples (
                    run_id      TEXT NOT NULL,
                    at          TEXT NOT NULL,
                    elapsed_ms  INTEGER NOT NULL,
                    phase       TEXT NOT NULL,
                    json        TEXT NOT NULL
                )""");
        // The report as a person reads it, written when the run finishes. Kept rather than
        // rendered on demand because the renderer takes the report object, which exists only while
        // the run is in memory -- and a report nobody can hand to somebody else is half a feature.
        // SQLite has no ADD COLUMN IF NOT EXISTS, so a database written by an earlier version is
        // brought up to date by asking it what it has.
        addColumnIfMissing(template, "runs", "report_html", "TEXT");
        addColumnIfMissing(template, "runs", "report_md", "TEXT");

        template.execute("CREATE INDEX IF NOT EXISTS samples_by_run ON samples (run_id)");
        template.execute("CREATE INDEX IF NOT EXISTS runs_by_started ON runs (started_at DESC)");

        return template;
    }

    /**
     * @param template the database
     * @param table which table
     * @param column the column it should have
     * @param type its type
     */
    private static void addColumnIfMissing(JdbcTemplate template, String table, String column,
            String type) {
        List<String> existing = template.query("PRAGMA table_info(" + table + ")",
                (rs, row) -> rs.getString("name"));
        if (!existing.contains(column)) {
            template.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}
