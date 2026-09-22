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

import org.acemq.workloads.scenario.ScenarioReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("redaction")
class RedactionTest {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final PrintStream target = new PrintStream(sink, true, StandardCharsets.UTF_8);
    private final PrintStream stream = Redaction.wrap(target);

    private String printed() {
        stream.flush();
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("takes the password out of an AMQP URL")
    void amqpUrl() {
        stream.println("could not connect to amqp://guest:hunter2@localhost:5672");
        assertThat(printed())
                .doesNotContain("hunter2")
                .contains("amqp://guest:***@localhost:5672");
    }

    @Test
    @DisplayName("and out of a management URL, which is a URL like any other")
    void managementUrl() {
        // The password in a workload file's `management:` section reaches the terminal by the
        // same routes and has never been redacted anywhere. There is nothing AMQP-specific
        // about the rule, so there is nothing AMQP-specific about where it applies.
        stream.println("queue depth unavailable at http://admin:letmein@localhost:15672");
        assertThat(printed()).doesNotContain("letmein").contains("http://admin:***@");
    }

    @Test
    @DisplayName("leaves a URL with no password alone")
    void noPassword() {
        stream.println("running against amqp://localhost:5672");
        assertThat(printed()).contains("amqp://localhost:5672");
    }

    @Test
    @DisplayName("redacting twice changes nothing the second time")
    void idempotent() {
        // The banner redacts before printing and the stream redacts again on the way out.
        // Both are worth keeping, so the second pass has to be a no-op.
        stream.println(ScenarioReader.redact("amqp://guest:hunter2@localhost:5672"));
        assertThat(printed()).contains("amqp://guest:***@localhost:5672").doesNotContain("***:***");
    }

    @Test
    @DisplayName("holds a line back until it is whole, so a URL cannot leak at a seam")
    void acrossWrites() {
        // Written a character at a time, which is what a large report does to a stream in
        // effect: no single write contains the whole URL.
        for (char c : "failed: amqp://guest:hunter2@localhost:5672\n".toCharArray()) {
            stream.print(c);
        }
        assertThat(printed()).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("a line that never ends is still printed")
    void unterminatedLine() {
        // A prompt, or the last line of something that was interrupted. Withholding it to be
        // safe would be a stream that silently eats output.
        stream.print("still here");
        assertThat(printed()).isEqualTo("still here");
    }

    @Test
    @DisplayName("passes ordinary output through unchanged")
    void ordinaryOutput() {
        stream.println("PASSED — 1 workload");
        assertThat(printed()).isEqualTo("PASSED — 1 workload" + System.lineSeparator());
    }
}
