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
package org.acemq.workloads.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.acemq.workloads.Sample;
import org.acemq.workloads.metrics.LatencySummary;
import org.junit.jupiter.api.Test;

/**
 * The reading a watcher parses.
 *
 * <p>These lines are read by the chaos drills, which compare the five languages' standing
 * loads against each other, so a field that means one thing here and another in Go's load
 * is worse than a missing field: it produces a comparison nobody can tell is wrong.
 *
 * <p>That happened with {@code failed}. This load counted a send declined for back
 * pressure as a failure while Go and .NET counted it apart as {@code refused}, and under
 * one fault this load reported 92,673 failures where the others reported about twenty.
 */
class SampleLinesTest {

    private static Sample reading(long failed, long refused) {
        return new Sample(
                Instant.parse("2026-10-03T12:00:00Z"),
                Duration.ofSeconds(30),
                Sample.Phase.MEASURING,
                1_000, 990, failed, refused, 985,
                200.0, 199.5,
                LatencySummary.empty("end-to-end"),
                LatencySummary.empty("send lag"),
                7L,
                false);
    }

    private static String emit(Sample sample) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            new SampleLines(out).onSample(sample);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void reportsRefusedApartFromFailed() {
        String line = emit(reading(3, 41));

        assertThat(line).contains("\"failed\":3");
        assertThat(line).contains("\"refused\":41");
    }

    @Test
    void reportsRefusedEvenWhenThereHaveBeenNone() {
        // Absent and zero are different claims. A watcher that sees no `refused` key
        // cannot tell a load with nothing to report from one that does not count it,
        // which is exactly the ambiguity this field was added to remove.
        assertThat(emit(reading(0, 0))).contains("\"refused\":0");
    }

    @Test
    void keepsTheFieldsAWatcherAlreadyReads() {
        String line = emit(reading(0, 0));

        assertThat(line)
                .contains("\"published\":1000")
                .contains("\"confirmed\":990")
                .contains("\"consumed\":985")
                .contains("\"blocked\":false");
    }
}
