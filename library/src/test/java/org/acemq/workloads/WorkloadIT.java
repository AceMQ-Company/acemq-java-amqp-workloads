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
package org.acemq.workloads;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.workloads.cli.WorkloadFile;
import org.acemq.workloads.rules.Objective;
import org.acemq.workloads.rules.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A real broker, real messages, and the numbers that come out.
 *
 * <p>The rates here are deliberately modest. A container on a laptop shared with the JVM running
 * the generator is not a capacity measurement, and asserting a throughput figure against it
 * would produce a test that fails on somebody else's machine for reasons that have nothing to do
 * with the code. What is asserted is that the <em>mechanism</em> works: that messages flow, that
 * latency is recorded from the intended send time, and that the validity rules fire when the run
 * is not measuring what it claims.
 */
@Testcontainers
@DisplayName("a workload against a real broker")
class WorkloadIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String amqpUrl() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
    }

    private static String managementUrl() {
        return "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);
    }

    @Test
    @Timeout(180)
    @DisplayName("messages flow, and the report describes what happened")
    void endToEnd() {
        WorkloadReport report = Workload.named("it-basic")
                .topology(t -> t
                        .exchange("wl.orders", "topic")
                        .queue("wl.orders.new")
                        .boundTo("wl.orders", "order.created"))
                .publishers(p -> p.threads(2).rate(2_000).messageSize(512))
                .consumers(c -> c.concurrency(4).prefetch(100))
                .management(managementUrl(), "guest", "guest")
                .warmup(Duration.ofSeconds(3))
                .runFor(Duration.ofSeconds(10))
                .run(amqpUrl());

        assertThat(report.published()).isPositive();
        assertThat(report.consumed()).isPositive();
        assertThat(report.failed()).isZero();

        // Latency was recorded, and it is a real measurement rather than a zero.
        assertThat(report.endToEnd().isEmpty()).isFalse();
        assertThat(report.endToEnd().p50()).isGreaterThan(Duration.ZERO);
        assertThat(report.endToEnd().p99()).isGreaterThanOrEqualTo(report.endToEnd().p50());

        // The schedule was kept, so the run is a valid measurement of something.
        assertThat(report.sendLag().isEmpty()).isFalse();
        assertThat(report.isValid()).isTrue();

        assertThat(report.queueDepthAtEnd()).isPresent();
        assertThat(report.format()).contains("it-basic").contains("offered 2,000/s");
    }

    @Test
    @Timeout(180)
    @DisplayName("a rate the client cannot offer invalidates the run rather than blaming the broker")
    void impossibleRateIsInvalidNotFailed() {
        // The "we need 300,000 a second" case, on hardware that cannot produce it from one
        // thread. The finding that matters is that the generator never offered the load --
        // reporting this as the broker missing the objective would blame the wrong machine.
        WorkloadReport report = Workload.named("it-impossible")
                .topology(t -> t.queue("wl.impossible").routingKey("wl.impossible"))
                .publishers(p -> p.threads(1).rate(2_000_000).messageSize(1024))
                .consumers(c -> c.concurrency(2).prefetch(200))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(10))
                .expect(Objective.throughputAtLeast(2_000_000))
                .run(amqpUrl());

        assertThat(report.isValid()).isFalse();
        assertThat(report.passed()).isFalse();

        // Worst first: the invalidity, not the missed objective.
        assertThat(report.findings().get(0).severity()).isEqualTo(Severity.INVALID);
        assertThat(report.findings().get(0).rule()).isEqualTo("generator-kept-up");
        assertThat(report.findings().get(0).implication())
                .contains("the configured load was never offered");

        assertThat(report.format()).contains("INVALID");
    }

    @Test
    @Timeout(180)
    @DisplayName("a workload with no consumers measures ingest and says latency is unavailable")
    void publishOnly() {
        WorkloadReport report = Workload.named("it-publish-only")
                .topology(t -> t.queue("wl.ingest").routingKey("wl.ingest"))
                .publishers(p -> p.threads(2).rate(1_000).messageSize(256))
                .consumers(ConsumerSpec::none)
                .management(managementUrl(), "guest", "guest")
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(8))
                .run(amqpUrl());

        assertThat(report.published()).isPositive();
        assertThat(report.consumed()).isZero();
        // No consumer means no end-to-end latency, and the report says so rather than
        // presenting zeroes as a measurement.
        assertThat(report.endToEnd().isEmpty()).isTrue();
        assertThat(report.consumersEnabled()).isFalse();

        // Everything published is still sitting in the queue, which is the point.
        assertThat(report.queueDepthAtEnd()).hasValueSatisfying(depth ->
                assertThat(depth).isPositive());
    }

    @Test
    @Timeout(180)
    @DisplayName("an objective that is met passes, and one that is not fails with the shortfall")
    void objectives() {
        WorkloadReport report = Workload.named("it-objectives")
                .topology(t -> t.queue("wl.objectives").routingKey("wl.objectives"))
                .publishers(p -> p.threads(2).rate(1_000).messageSize(256))
                .consumers(c -> c.concurrency(4).prefetch(50))
                .warmup(Duration.ofSeconds(3))
                .runFor(Duration.ofSeconds(10))
                .expect(Objective.throughputAtLeast(100_000))
                .run(amqpUrl());

        // The run is sound; the objective is simply not met at an offered rate of 1,000.
        assertThat(report.isValid()).isTrue();
        assertThat(report.passed()).isFalse();
        assertThat(report.problems())
                .anySatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.FAILED);
                    assertThat(f.rule()).isEqualTo("throughput>=100000");
                    assertThat(f.implication()).contains("% of the required rate");
                });
    }

    @Test
    @Timeout(180)
    @DisplayName("a slow handler shows up as end-to-end latency, not as a publish problem")
    void slowHandler() {
        WorkloadReport report = Workload.named("it-slow-handler")
                .topology(t -> t.queue("wl.slow").routingKey("wl.slow"))
                .publishers(p -> p.threads(1).rate(500).messageSize(256))
                // One consumer, 5ms per message: 200/s of capacity against 500/s offered.
                .consumers(c -> c.concurrency(1).prefetch(10)
                        .handlerTime(Duration.ofMillis(5)))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(10))
                .run(amqpUrl());

        assertThat(report.published()).isGreaterThan(report.consumed());
        // Publishing was never the problem, and the report must not suggest it was.
        assertThat(report.failed()).isZero();
        assertThat(report.publishLatency().p99()).isLessThan(Duration.ofSeconds(1));

        assertThat(report.findings())
                .anySatisfy(f -> {
                    assertThat(f.rule()).isEqualTo("consumers-kept-up");
                    assertThat(f.implication()).contains("run's length");
                });
    }

    /**
     * What the broker was actually told, rather than what the report says it was told.
     *
     * <p>These exist because of a bug that no report could have caught. A workload file's
     * {@code queueType} and {@code arguments} were parsed, validated, and then dropped on the way
     * to the scenario the engine runs, so every queue was declared classic — while the report
     * printed back the type that had been <em>asked for</em>. Two workloads differing only in
     * {@code queueType} therefore compared classic against classic and agreed with each other,
     * which is the worst way for a measurement to be wrong. {@code confirms: false} was dropped
     * the same way, one layer lower.
     *
     * <p>So the assertion has to come from somewhere the code under test does not write. The
     * management API is that place: it reports what the broker holds, and it does not care what
     * the workload file said.
     */
    @Nested
    @DisplayName("what the broker was actually told")
    class WhatTheBrokerWasActuallyTold {

        @Test
        @Timeout(180)
        @DisplayName("a workload file that asks for a quorum queue gets one")
        void queueTypeAndArgumentsReachTheBroker(@TempDir Path directory) throws IOException {
            // Through the file, not the builder: the file is the surface the bug was on, and a
            // test that starts at the builder would have passed throughout.
            Path file = directory.resolve("quorum.yaml");
            Files.writeString(file, """
                    name: it-declares-quorum
                    broker: %s
                    topology:
                      queue: wl.declared.quorum
                      routingKey: wl.declared.quorum
                      queueType: quorum
                      arguments:
                        x-max-length: 100000
                    publishers: { threads: 1, rate: 200, messageSize: 256 }
                    consumers: { concurrency: 2, prefetch: 50 }
                    warmup: 1s
                    runFor: 5s
                    """.formatted(amqpUrl()));

            WorkloadFile parsed = WorkloadFile.read(file);
            parsed.workloads().get(0).run(parsed.brokerUrl(0));

            try (RabbitAdmin admin = RabbitAdmin.connect(managementUrl(), "guest", "guest")) {
                QueueInfo queue = admin.queue("wl.declared.quorum").orElseThrow();

                assertThat(queue.type()).isEqualTo("quorum");
                assertThat(queue.argument("x-queue-type")).isEqualTo("quorum");
                // A number in YAML has to survive as a number. An x-max-length of "100000" is
                // refused by the broker, so this would have failed loudly rather than quietly --
                // but only once the argument reached it at all.
                assertThat(queue.argument("x-max-length")).isEqualTo(100_000);
            }
        }

        @Test
        @Timeout(180)
        @DisplayName("the default is still a classic queue, and nothing invents an x-queue-type")
        void theDefaultIsClassic() {
            Workload.named("it-declares-classic")
                    .topology(t -> t.queue("wl.declared.classic").routingKey("wl.declared.classic"))
                    .publishers(p -> p.threads(1).rate(200).messageSize(256))
                    .consumers(c -> c.concurrency(2).prefetch(50))
                    .warmup(Duration.ofSeconds(1))
                    .runFor(Duration.ofSeconds(5))
                    .run(amqpUrl());

            try (RabbitAdmin admin = RabbitAdmin.connect(managementUrl(), "guest", "guest")) {
                QueueInfo queue = admin.queue("wl.declared.classic").orElseThrow();

                assertThat(queue.type()).isEqualTo("classic");
                // The quorum case above asserts the same field. Both have to be read from the
                // broker for the pair to mean anything: a test that only ever asks about the
                // queue it expects to be quorum would still pass if every queue were.
                assertThat(queue.argument("x-max-length")).isNull();
            }
        }

        @Test
        @Timeout(180)
        @DisplayName("confirms: false reaches the channel the broker sees")
        void confirmsReachTheChannel() {
            assertThat(channelConfirmFlagsDuringA(false))
                    .describedAs("every publishing channel, with confirms switched off")
                    .isNotEmpty()
                    .containsOnly(false);
        }

        @Test
        @Timeout(180)
        @DisplayName("and confirms: true still does")
        void confirmsOnReachTheChannel() {
            assertThat(channelConfirmFlagsDuringA(true))
                    .describedAs("every publishing channel, with confirms switched on")
                    .isNotEmpty()
                    .containsOnly(true);
        }

        /**
         * Runs a workload and asks the broker, while it is still running, whether the channels
         * publishing to it are in confirm mode.
         *
         * <p>It has to be asked during the run: a channel that has closed is not in
         * {@code /api/channels} any more, so a report read afterwards is the only thing left to
         * believe, and the report is what was lying.
         *
         * @param confirms what the workload asks for
         * @return the confirm flag of every channel with something unconfirmed or unacknowledged
         *     on it, as the broker sees them
         */
        private List<Boolean> channelConfirmFlagsDuringA(boolean confirms) {
            String queue = "wl.confirms." + confirms;
            RunHandle handle = Workload.named("it-confirms-" + confirms)
                    .topology(t -> t.queue(queue).routingKey(queue))
                    .publishers(p -> p.threads(2).rate(400).messageSize(256).confirms(confirms))
                    .consumers(ConsumerSpec::none)
                    .warmup(Duration.ofSeconds(1))
                    // Long enough to be asked about, and stopped as soon as it has been.
                    .runFor(Duration.ofMinutes(2))
                    .build()
                    .start(amqpUrl(), RunListener.NONE);

            try (RabbitAdmin admin = RabbitAdmin.connect(managementUrl(), "guest", "guest")) {
                List<Boolean> flags = List.of();
                long deadline = System.currentTimeMillis() + 60_000;
                while (flags.isEmpty() && System.currentTimeMillis() < deadline) {
                    sleep(500);
                    flags = admin.channels().stream()
                            // Consumers are switched off, so a channel doing anything at all is
                            // one of the publishers'.
                            .filter(c -> c.messagesUnconfirmed() > 0 || c.confirm())
                            .map(ChannelInfo::confirm)
                            .toList();
                    // With confirms off there is nothing unconfirmed and nothing in confirm mode,
                    // so the filter above finds nothing and the absence is the answer. Wait for
                    // the run to have opened its channels, then read them all.
                    if (flags.isEmpty() && !confirms) {
                        flags = admin.channels().stream()
                                .map(ChannelInfo::confirm)
                                .toList();
                    }
                }
                return flags;
            } finally {
                handle.stop();
                handle.report().join();
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
