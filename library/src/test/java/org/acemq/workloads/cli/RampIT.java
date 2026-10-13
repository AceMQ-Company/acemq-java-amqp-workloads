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
import java.time.Duration;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.workloads.Ramp;
import org.acemq.workloads.RampReport;
import org.acemq.workloads.Workload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A ramp against a real broker with a deliberately throttled consumer.
 *
 * <p>One consumer that spends 5ms on every message cannot take more than 200 a second, whatever the
 * broker does. That is the one capacity number a laptop can be relied on to reproduce, so it is the
 * one asserted: the ramp must stop at the step past it, and not before.
 */
@Testcontainers
@DisplayName("a ramp against a real broker")
class RampIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String amqpUrl() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
    }

    @Test
    @Timeout(300)
    @DisplayName("stops at the first step a 5ms consumer cannot keep up with")
    void stopsAtTheThrottle() {
        Workload workload = Workload.named("ramp-it")
                .topology(t -> t.queue("ramp.it.slow"))
                .publishers(p -> p.threads(1).messageSize(128))
                .consumers(c -> c.concurrency(1).prefetch(10).handlerTime(Duration.ofMillis(5)))
                .build();

        RampReport report = Ramp.of(workload)
                .startAt(50).stepBy(50).maxRate(400)
                .stepFor(Duration.ofSeconds(6))
                .warmupEachStep(Duration.ofSeconds(2))
                .run(amqpUrl());
        // Kept in the test output: when this fails on a busy runner, the steps say why.
        System.out.println(report.format());

        // 200/s is the arithmetic ceiling; parking for 5ms costs a little more than 5ms, so 200 can
        // land either side. 150 must hold and 250 must not.
        assertThat(report.highestSustainedRate()).isBetween(150L, 200L);
        assertThat(report.stoppingStep()).get()
                .extracting(RampReport.Step::rate).isIn(200L, 250L);
        assertThat(report.stoppedBy()).contains("kept-pace");
        assertThat(report.isValid()).isTrue();
        assertThat(report.reachedMaxRate()).isFalse();
        // Every step was measured from a real run, with latency recorded from the schedule.
        assertThat(report.steps()).allSatisfy(step -> {
            assertThat(step.report().published()).isPositive();
            assertThat(step.report().endToEnd().isEmpty()).isFalse();
        });
    }

    @Test
    @Timeout(300)
    @DisplayName("from a file: the ramp and its rules run, exit 0, and the JSON report has the steps")
    void fromAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ramp.yaml");
        Files.writeString(file, """
                name: ramp-file
                broker: %s
                topology: { queue: ramp.it.file }
                publishers: { threads: 1, messageSize: 128 }
                consumers: { concurrency: 2 }
                warmup: 1s
                runFor: 5s
                rules:
                  - name: nothing-failed
                    metric: failed
                    above: 0
                ramp:
                  start: 100
                  step: 100%%
                  maxRate: 200
                """.formatted(amqpUrl()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(new String[] {"-f", file.toString(), "--report", dir.toString(),
                "--format", "json,md,html"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(printed + err.toString(StandardCharsets.UTF_8)).isEqualTo(Cli.OK);
        assertThat(printed).contains("step 100/s").contains("step 200/s")
                .contains("highest sustained rate: 200/s").contains("reached maxRate");

        Path json;
        try (Stream<Path> files = Files.list(dir)) {
            json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        JsonNode ramp = new ObjectMapper().readTree(json.toFile()).path("ramps").get(0);
        assertThat(ramp.path("highestSustainedRatePerSecond").asLong()).isEqualTo(200);
        assertThat(ramp.path("stoppedBy").asText()).isEqualTo("maxRate");
        assertThat(ramp.path("steps")).hasSize(2);
        assertThat(ramp.path("steps").get(0).path("endToEnd").path("p99Micros").asLong())
                .isPositive();
    }
}
