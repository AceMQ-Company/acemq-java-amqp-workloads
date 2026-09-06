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
}
