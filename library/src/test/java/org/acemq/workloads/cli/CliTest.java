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
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the command line")
class CliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Cli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("no arguments prints usage rather than doing something")
    void noArguments() {
        assertThat(run()).isEqualTo(Cli.OK);
        assertThat(stdout()).contains("usage:").contains("-f, --file");
    }

    @Test
    @DisplayName("an unknown option is a config error, not a crash")
    void unknownOption() {
        assertThat(run("--nonsense")).isEqualTo(Cli.BAD_CONFIG);
        assertThat(stderr()).contains("unknown option '--nonsense'");
    }

    @Test
    @DisplayName("a missing file is a config error and says which file")
    void missingFile(@TempDir Path dir) {
        assertThat(run("-f", dir.resolve("nope.yaml").toString())).isEqualTo(Cli.BAD_CONFIG);
        assertThat(stderr()).contains("could not read");
    }

    @Test
    @DisplayName("--dry-run resolves the configuration and touches no broker")
    void dryRun(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("workload.yaml");
        Files.writeString(file, """
                name: dry
                broker: amqp://guest:hunter2@localhost:5672
                topology: { queue: q }
                publishers: { rate: 1000, threads: 2 }
                consumers: { concurrency: 4 }
                runFor: 30s
                """);

        // No broker is running on that URL, and this still succeeds.
        assertThat(run("-f", file.toString(), "--dry-run")).isEqualTo(Cli.OK);
        assertThat(stdout())
                .contains("dry")
                .contains("rate=1000/s")
                .contains("runFor      30s")
                .doesNotContain("hunter2");
    }

    @Test
    @DisplayName("PDF is refused with the reason, rather than silently ignored")
    void pdfRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.yaml");
        Files.writeString(file, "name: x\nbroker: amqp://localhost\nrunFor: 30s\n");

        assertThat(run("-f", file.toString(), "--format", "pdf")).isEqualTo(Cli.BAD_CONFIG);
        assertThat(stderr())
                .contains("PDF is deliberately not supported")
                .contains("print it from a browser");
    }

    @Test
    @DisplayName("an unreachable broker exits distinctly from a failed objective")
    void unreachableBroker(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.yaml");
        Files.writeString(file, """
                name: unreachable
                broker: amqp://guest:guest@127.0.0.1:1
                topology: { queue: q }
                publishers: { rate: 10 }
                consumers: { concurrency: 1 }
                warmup: 0s
                runFor: 1s
                """);

        // 4, not 1: a pipeline must not read "the broker refused the load" when the broker
        // was never reached at all.
        assertThat(run("-f", file.toString(), "--quiet")).isEqualTo(Cli.BROKER_UNREACHABLE);
        assertThat(stderr()).contains("the run failed");
    }

    @Test
    @DisplayName("a bad setting names the setting")
    void badSetting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.yaml");
        Files.writeString(file, """
                name: typo
                broker: amqp://localhost
                consumers: { prefech: 10 }
                runFor: 30s
                """);

        assertThat(run("-f", file.toString())).isEqualTo(Cli.BAD_CONFIG);
        assertThat(stderr()).contains("consumers.prefech");
    }

    @Test
    @DisplayName("--help and --version answer without a file")
    void helpAndVersion() {
        assertThat(run("--help")).isEqualTo(Cli.OK);
        assertThat(stdout()).contains("exit codes:");

        out.reset();
        assertThat(run("--version")).isEqualTo(Cli.OK);
        assertThat(stdout()).contains("acemq-workload");
    }

    /**
     * Every one of these asks the same question of a different route out: did the password
     * reach the terminal.
     *
     * <p>Deliberately not "does this message read as expected". The leak these pin was never a
     * wrong sentence — it was one failure path that did not call the redaction every other path
     * called, printing {@code could not connect to amqp://guest:hunter2@localhost:1} one line
     * below a banner that had redacted the identical URL. A test matching on the message would
     * have passed on the day the next such path was written, which is the whole problem: the
     * password does not care which sentence carries it, and neither does the CI log it lands in.
     *
     * <p>So the assertion is the absence of the secret from everything both streams produced,
     * and each case is a different way of getting the URL in front of a printer: a library
     * quoting it back, a parser quoting the line it choked on, and the two file formats.
     */
    @Nested
    @DisplayName("never prints a password")
    class Redacting {

        /** Distinctive enough that finding it anywhere in the output means it came from here. */
        private static final String SECRET = "n0t-f0r-the-log";

        private void assertSilentAbout(String secret) {
            assertThat(stdout() + stderr())
                    .as("everything the command line printed")
                    .doesNotContain(secret);
        }

        @Test
        @DisplayName("not when a broker refuses to be reached")
        void unreachable(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("w.yaml");
            Files.writeString(file, """
                    name: unreachable
                    broker: amqp://guest:%s@127.0.0.1:1
                    topology: { queue: q }
                    publishers: { rate: 10 }
                    consumers: { concurrency: 1 }
                    warmup: 0s
                    runFor: 1s
                    """.formatted(SECRET));

            assertThat(run("-f", file.toString())).isEqualTo(Cli.BROKER_UNREACHABLE);
            assertSilentAbout(SECRET);
        }

        @Test
        @DisplayName("not when the parser quotes the line it choked on")
        void malformedFile(@TempDir Path dir) throws Exception {
            // Jackson puts a slice of the source into its own message, and the broker line is
            // very often inside that slice. Nothing in this project wrote that message.
            Path file = dir.resolve("w.yaml");
            Files.writeString(file, """
                    name: broken
                    broker: amqp://guest:%s@localhost:5672
                    topology: { queue: q
                    runFor: 30s
                    """.formatted(SECRET));

            assertThat(run("-f", file.toString())).isEqualTo(Cli.BAD_CONFIG);
            assertSilentAbout(SECRET);
        }

        @Test
        @DisplayName("not when an unknown setting is refused")
        void unknownSetting(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("w.yaml");
            Files.writeString(file, """
                    name: typo
                    broker: amqp://guest:%s@localhost:5672
                    consumers: { prefech: 10 }
                    runFor: 30s
                    """.formatted(SECRET));

            assertThat(run("-f", file.toString())).isEqualTo(Cli.BAD_CONFIG);
            assertSilentAbout(SECRET);
        }

        @Test
        @DisplayName("not from a scenario file, which takes a different route entirely")
        void scenarioFile(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("s.yaml");
            Files.writeString(file, """
                    name: unreachable-scenario
                    broker: amqp://guest:%s@127.0.0.1:1
                    exchanges: [ { name: ex, type: topic } ]
                    queues:
                      - name: q
                        bindings: [ { exchange: ex, routingKey: k } ]
                        consumers: { concurrency: 1 }
                    producers:
                      - { name: p, exchange: ex, routingKeys: [k], rate: 10 }
                    warmup: 0s
                    runFor: 1s
                    """.formatted(SECRET));

            assertThat(run("-f", file.toString())).isEqualTo(Cli.BROKER_UNREACHABLE);
            assertSilentAbout(SECRET);
        }

        @Test
        @DisplayName("not from a scenario the studio would not have written")
        void malformedScenario(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("s.yaml");
            Files.writeString(file, """
                    name: nonsense
                    broker: amqp://guest:%s@localhost:5672
                    producers: [ { name: p, exchange: ex, routingKeys: [k], rait: 10 } ]
                    """.formatted(SECRET));

            assertThat(run("-f", file.toString())).isEqualTo(Cli.BAD_CONFIG);
            assertSilentAbout(SECRET);
        }

        @Test
        @DisplayName("not even from --broker, which never goes near a file")
        void fromTheCommandLine(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("s.yaml");
            Files.writeString(file, """
                    name: overridden
                    exchanges: [ { name: ex, type: topic } ]
                    queues:
                      - name: q
                        bindings: [ { exchange: ex, routingKey: k } ]
                        consumers: { concurrency: 1 }
                    producers:
                      - { name: p, exchange: ex, routingKeys: [k], rate: 10 }
                    warmup: 0s
                    runFor: 1s
                    """);

            assertThat(run("-f", file.toString(),
                    "--broker", "amqp://guest:" + SECRET + "@127.0.0.1:1"))
                    .isEqualTo(Cli.BROKER_UNREACHABLE);
            assertSilentAbout(SECRET);
        }
    }
}
