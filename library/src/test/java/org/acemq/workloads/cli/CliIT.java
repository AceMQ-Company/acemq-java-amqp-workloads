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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * What the command line exits with when a real broker says no.
 *
 * <p>This needs a real broker and cannot be faked: the whole point is that the refusal comes
 * back through the transport, the client library and two layers of wrapping before anything
 * here decides what to call it, and a hand-written exception would only prove that the
 * classifier agrees with the test's idea of what a broker sounds like.
 */
@Testcontainers
@DisplayName("the command line against a broker that refuses")
class CliIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Cli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static String amqpUrl() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
    }

    private Path workload(Path dir, String name, String exchangeType) throws Exception {
        Path file = dir.resolve(name + ".yaml");
        Files.writeString(file, """
                name: %s
                broker: %s
                topology:
                  exchange: it-clash
                  exchangeType: %s
                  queue: it-clash-q
                  routingKey: k
                publishers: { rate: 50 }
                consumers: { concurrency: 1 }
                warmup: 0s
                runFor: 2s
                """.formatted(name, amqpUrl(), exchangeType));
        return file;
    }

    @Test
    @Timeout(180)
    @DisplayName("a refused redeclaration is exit 3, not exit 4")
    void refusedDeclarationIsABadFile(@TempDir Path dir) throws Exception {
        // First run declares the exchange as a topic and passes.
        assertThat(run("-f", workload(dir, "first", "topic").toString(), "--quiet"))
                .isEqualTo(Cli.OK);

        out.reset();
        err.reset();

        // The second asks for the same exchange as a direct, which the broker refuses. It is a
        // broker that answered: reporting it as unreachable would send the reader to check
        // firewalls and hostnames for a mistake sitting in the file they are holding.
        assertThat(run("-f", workload(dir, "second", "direct").toString(), "--quiet"))
                .isEqualTo(Cli.BAD_CONFIG);

        String printed = out.toString(StandardCharsets.UTF_8)
                + err.toString(StandardCharsets.UTF_8);
        assertThat(printed)
                .doesNotContain("could not be reached")
                .contains("the broker refused")
                // And what the broker actually said, which is the only sentence that names the
                // setting to change.
                .contains("PRECONDITION_FAILED")
                .contains("it-clash");
    }
}
