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
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.studio.scenario.ScenarioJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Saved scenarios. */
@Repository
public class ScenarioStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ScenarioStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * A saved scenario, as the list shows it.
     *
     * @param id its identifier
     * @param name what it is called
     * @param description what it is for
     * @param createdAt when it was first saved
     * @param updatedAt when it last changed
     */
    public record Summary(String id, String name, String description, Instant createdAt,
            Instant updatedAt) {
    }

    /** @return every saved scenario, most recently changed first */
    public List<Summary> list() {
        return jdbc.query("""
                SELECT id, name, description, created_at, updated_at
                FROM scenarios ORDER BY updated_at DESC""",
                (rs, row) -> new Summary(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))));
    }

    /**
     * @param id a scenario
     * @return it, if it is there
     */
    public Optional<ScenarioJson> find(String id) {
        return jdbc.query("SELECT json FROM scenarios WHERE id = ?",
                        (rs, row) -> read(rs.getString("json")), id)
                .stream().findFirst();
    }

    /**
     * Saves a new scenario or replaces one.
     *
     * @param id the identifier, or null for a new one
     * @param scenario what to save
     * @return the identifier it was saved under
     */
    public String save(String id, ScenarioJson scenario) {
        String identifier = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        String now = Instant.now().toString();
        String body = write(scenario);

        // One statement rather than a read and a branch: two studio windows saving the same
        // scenario is a normal thing to do, and the last write should win rather than fail.
        jdbc.update("""
                INSERT INTO scenarios (id, name, description, json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    description = excluded.description,
                    json = excluded.json,
                    updated_at = excluded.updated_at""",
                identifier,
                scenario.name() == null ? "scenario" : scenario.name(),
                scenario.description() == null ? "" : scenario.description(),
                body, now, now);

        return identifier;
    }

    /**
     * @param id the scenario to forget
     * @return whether there was one
     */
    public boolean delete(String id) {
        return jdbc.update("DELETE FROM scenarios WHERE id = ?", id) > 0;
    }

    private ScenarioJson read(String body) {
        try {
            return json.readValue(body, ScenarioJson.class);
        } catch (Exception e) {
            throw new IllegalStateException("a saved scenario could not be read back", e);
        }
    }

    private String write(ScenarioJson scenario) {
        try {
            return json.writeValueAsString(scenario);
        } catch (Exception e) {
            throw new IllegalStateException("this scenario could not be saved", e);
        }
    }
}
