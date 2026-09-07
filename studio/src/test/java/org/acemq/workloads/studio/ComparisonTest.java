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
package org.acemq.workloads.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.studio.run.Comparison;
import org.acemq.workloads.studio.run.ReportJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two runs, side by side.
 *
 * <p>What is checked here is the part a person would get wrong at the moment they are least able
 * to: which direction is an improvement. A latency that went up is worse; a rate that went up is
 * better; and a comparison reporting both as "+12%" makes the reader remember which is which.
 */
@DisplayName("comparing two runs")
class ComparisonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a faster queue is better and a slower one is worse")
    void knowsWhichWayIsBetter() throws Exception {
        List<Comparison.Row> rows = Comparison.of(
                report(2_000, 10.0), report(2_400, 14.0));

        assertThat(row(rows, "orders.q", "consumed/s").verdict()).isEqualTo("better");
        assertThat(row(rows, "orders.q", "consumed/s").change()).isCloseTo(0.2, within());

        // The same +40% on latency is the opposite answer.
        assertThat(row(rows, "orders.q", "p99").verdict()).isEqualTo("worse");
        assertThat(row(rows, "orders.q", "p99").higherIsBetter()).isFalse();
    }

    // Two runs of the same configuration differ by a few percent on ordinary hardware. A tool
    // that called that a regression would be switched off inside a week.
    @Test
    @DisplayName("a difference inside the noise is not a change")
    void smallDifferencesAreNotChanges() throws Exception {
        List<Comparison.Row> rows = Comparison.of(
                report(2_000, 10.0), report(2_060, 10.3));

        assertThat(row(rows, "orders.q", "consumed/s").verdict()).isEqualTo("same");
        assertThat(row(rows, "orders.q", "p99").verdict()).isEqualTo("same");
    }

    @Test
    @DisplayName("a queue only one run had is reported rather than dropped")
    void keepsANodeOnlyOneSideHas() throws Exception {
        JsonNode before = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "producers": [], "queues": [
                  {"name": "kept", "consumeRate": 100, "endToEnd": {"count": 5, "p99": 2}},
                  {"name": "removed", "consumeRate": 50, "endToEnd": {"count": 5, "p99": 3}}]}""");
        JsonNode after = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "producers": [], "queues": [
                  {"name": "kept", "consumeRate": 100, "endToEnd": {"count": 5, "p99": 2}}]}""");

        List<Comparison.Row> rows = Comparison.of(before, after);

        // Comparing a scenario with a queue against the same scenario without it is a comparison,
        // and dropping the queue would hide the only thing that changed.
        assertThat(row(rows, "removed", "consumed/s").verdict()).isEqualTo("missing");
        assertThat(row(rows, "removed", "consumed/s").b()).isNull();
    }

    // A queue that received nothing has no percentiles. Reporting its p99 as zero would make the
    // run that measured nothing look like the fastest one anybody had ever seen.
    @Test
    @DisplayName("a queue that handled nothing has no latency to compare")
    void doesNotInventPercentilesForAnEmptyQueue() throws Exception {
        JsonNode before = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "producers": [], "queues": [
                  {"name": "q", "consumeRate": 100, "endToEnd": {"count": 0}}]}""");
        JsonNode after = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "producers": [], "queues": [
                  {"name": "q", "consumeRate": 100, "endToEnd": {"count": 9, "p99": 4}}]}""");

        assertThat(row(Comparison.of(before, after), "q", "p99").verdict()).isEqualTo("missing");
    }

    @Test
    @DisplayName("a producer that started failing is worse, even from zero")
    void reportsAChangeFromZeroWithoutAPercentage() throws Exception {
        JsonNode before = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "queues": [], "producers": [
                  {"name": "p", "achievedRate": 1000, "failed": 0}]}""");
        JsonNode after = json.readTree("""
                {"totalPublished": 10, "totalConsumed": 10, "queues": [], "producers": [
                  {"name": "p", "achievedRate": 1000, "failed": 12}]}""");

        Comparison.Row failed = row(Comparison.of(before, after), "p", "failed");

        assertThat(failed.verdict()).isEqualTo("worse");
        // No percentage against zero: going from nothing to something is a change worth showing
        // and not one worth expressing as a ratio.
        assertThat(failed.change()).isNull();
    }

    /**
     * The field names, taken from the record the studio actually stores.
     *
     * <p>Written after a comparison read `p50Ms` — the name the library's report writer uses —
     * against reports the studio stores as {@link ReportJson}, which calls them `p50`. Every other
     * test passed, because they were built from JSON I had written by hand with the same wrong
     * names in it. A test that invents its own input can only ever check the code against itself.
     */
    @Test
    @DisplayName("reads the report the studio actually stores")
    void readsTheShapeTheStudioStores() throws Exception {
        ReportJson.LatencyJson slow = new ReportJson.LatencyJson(500, 2, 4, 40, 60, 90);
        ReportJson.LatencyJson quick = new ReportJson.LatencyJson(500, 1, 2, 10, 15, 20);

        JsonNode before = json.valueToTree(stored(slow));
        JsonNode after = json.valueToTree(stored(quick));

        List<Comparison.Row> rows = Comparison.of(before, after);

        assertThat(row(rows, "orders.q", "p99").a()).isEqualTo(40.0);
        assertThat(row(rows, "orders.q", "p99").b()).isEqualTo(10.0);
        assertThat(row(rows, "orders.q", "p99").verdict()).isEqualTo("better");
        assertThat(row(rows, "orders.q", "p50").verdict()).isEqualTo("better");
    }

    private static ReportJson stored(ReportJson.LatencyJson endToEnd) {
        ReportJson.LatencyJson none = new ReportJson.LatencyJson(0, 0, 0, 0, 0, 0);
        return new ReportJson("orders", "2026-09-07T00:00:00Z", 30_000, false, "passed", true,
                1_000, 1_000, 0, null,
                List.of(new ReportJson.ProducerJson("load", 1_000, 990, 1_000, 1_000, 0,
                        none, none)),
                List.of(new ReportJson.QueueJson("orders.q", "classic", 4, 1_000, 990,
                        endToEnd, 0L, 0L, false)),
                List.of());
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.001);
    }

    private static Comparison.Row row(List<Comparison.Row> rows, String node, String metric) {
        return rows.stream()
                .filter(r -> r.node().equals(node) && r.metric().equals(metric))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + node + " " + metric));
    }

    private JsonNode report(long consumeRate, double p99) throws Exception {
        return json.readTree("""
                {"totalPublished": 100, "totalConsumed": 100,
                 "producers": [{"name": "load", "achievedRate": %d, "failed": 0}],
                 "queues": [{"name": "orders.q", "consumeRate": %d,
                             "endToEnd": {"count": 100, "p50": 1, "p99": %s, "p999": 20}}]}"""
                .formatted(consumeRate, consumeRate, p99));
    }
}
