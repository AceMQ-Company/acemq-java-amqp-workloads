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
package org.acemq.workloads.endurance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A real soak, short: one real client process (this repository's own standing load, the jar the
 * build just made), a real broker, and every connection closed through the management API once
 * a cycle. What it proves is the part the unit tests fake: that the fault reaches a client, that
 * the client's pid is sampled from outside, and that the report says so.
 */
@Testcontainers
@DisplayName("endurance against a real broker and a real client")
class EnduranceIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    @TempDir
    Path ws;

    @Test
    void closesTheClientsConnectionsEveryCycleAndJudgesTheProcess() throws Exception {
        Path jar = Path.of("target/acemq-workload.jar").toAbsolutePath();
        assertThat(jar).as("the shaded jar, built in the package phase").exists();
        Path load = ws.resolve("load.yaml");
        Files.writeString(load, """
                name: endurance-it
                broker: ${DRILL_BROKER}
                topology: { queue: endurance.it, routingKey: endurance.it }
                publishers: { rate: 50, messageSize: 128, confirms: true }
                consumers: { concurrency: 1 }
                warmup: 0s
                runFor: 10m
                """);
        String amqp = "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
        Path yaml = ws.resolve("endurance.yaml");
        Files.writeString(yaml, """
                workspace: %s
                broker: %s
                management: %s
                cycles: 3
                cycleSeconds: 4
                warmupSeconds: 6
                sampleSeconds: 4
                cooldownSeconds: 4
                readingGapSeconds: 1
                startupSeconds: 60
                clients: [java]
                # a JVM's resident memory still climbs towards its heap in the first seconds,
                # which a three-cycle soak would read as growth
                allowances: { memoryFactor: 10 }
                launch:
                  java:
                    command: [java, -Xmx128m, -jar, "%s", -f, "%s", --emit-samples, --quiet]
                """.formatted(ws, amqp, BROKER.getHttpUrl(), jar, load));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EnduranceCli.run(new String[] {"-f", yaml.toString(), "--quiet"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String said = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertThat(code).as(said).isEqualTo(EnduranceCli.PASSED);
        assertThat(said).contains("PASSED — report: reports/soak-");

        Path report;
        try (Stream<Path> files = Files.list(ws.resolve("reports"))) {
            report = files.filter(p -> p.toString().endsWith(".md")).findFirst().orElseThrow();
        }
        String md = Files.readString(report);
        assertThat(md).contains("**PASSED**").contains("| java |")
                .contains("3 cycles, one every 4s, sampling every 4s.");
        // The fault did something: right after a close the broker held fewer connections than
        // it did settled -- the check that keeps a soak whose closes failed from passing.
        List<Long> after = Files.readAllLines(ws.resolve(".chaos").resolve(
                report.getFileName().toString().replace(".md", ".closes")))
                .stream().map(Long::parseLong).toList();
        // Not every one: a close can land while the client is between connections.
        assertThat(after).hasSize(3).contains(0L);
        assertThat(md).containsPattern("connections while settled: low \\d+, high [1-9]");
        assertThat(report.resolveSibling(report.getFileName().toString().replace(".md", ".json")))
                .exists();
        // and the load it started is gone
        assertThat(ws.resolve(".chaos/workload-java.pid")).doesNotExist();
    }
}
