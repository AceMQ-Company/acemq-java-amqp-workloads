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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code loads up}, {@code status --json} and {@code down} with the real Java standing load (the
 * jar this build just made) against a real broker: the part the fakes cannot show is that the
 * load is publishing by the time {@code up} returns.
 */
@Testcontainers
@DisplayName("loads against a real broker and the real Java load")
class LoadsIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    @TempDir
    Path ws;

    @Test
    void upIsPublishingStatusSaysSoAndDownStopsIt() throws Exception {
        Path jar = Path.of("target/acemq-workload.jar").toAbsolutePath();
        assertThat(jar).as("the shaded jar, built in the package phase").exists();
        Path load = ws.resolve("load.yaml");
        Files.writeString(load, """
                name: loads-it
                broker: ${DRILL_BROKER}
                topology: { queue: loads.it, routingKey: loads.it }
                publishers: { rate: 50, messageSize: 128, confirms: true }
                consumers: { concurrency: 1 }
                warmup: 0s
                runFor: 10m
                """);
        Path yaml = ws.resolve("loads.yaml");
        Files.writeString(yaml, """
                launch:
                  java:
                    command: [java, -Xmx128m, -jar, "%s", -f, "%s", --emit-samples, --quiet]
                """.formatted(jar, load));
        String amqp = "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
        common = new String[] {"-f", yaml.toString(), "--workspace", ws.toString(), "--clients", "java"};

        run("up", "--broker", amqp);
        try {
            assertThat(ws.resolve(".chaos/workload-java.pid")).exists();
            Thread.sleep(3000);
            String status = run("status", "--json");
            JsonNode java = new ObjectMapper().readTree(status).path("clients").get(0);
            assertThat(java.path("running").asBoolean()).as(status).isTrue();
            assertThat(java.path("lastSampleAgeSeconds").asDouble()).as(status).isLessThan(5);
            assertThat(java.path("publishRate").asDouble()).as(status).isGreaterThan(0);
        } finally {
            run("down");
        }
        assertThat(ws.resolve(".chaos/workload-java.pid")).doesNotExist();
    }

    private String[] common;

    /** @return stdout; anything but exit 0 fails the test with everything it said */
    private String run(String... head) {
        String[] args = new String[head.length + common.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(common, 0, args, head.length, common.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LoadsCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String said = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as("loads %s: %s%s", String.join(" ", args), said,
                err.toString(StandardCharsets.UTF_8)).isZero();
        return said;
    }
}
