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

import java.time.Duration;

import org.acemq.workloads.rules.Objective;
import org.acemq.workloads.rules.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
}
