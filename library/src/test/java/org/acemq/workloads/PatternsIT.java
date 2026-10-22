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
import static org.assertj.core.api.Assertions.withinPercentage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.acemq.amqp.api.RetryPolicy;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.workloads.rules.Objective;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("AceMQ patterns under load, against a real broker")
class PatternsIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String amqpUrl() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort();
    }

    @Test
    @Timeout(180)
    @DisplayName("a retry ladder retries through its rungs, recovers most, dead-letters the rest, loses none")
    void retryLadder() {
        WorkloadReport report = Workload.named("it-ladder")
                .topology(t -> t.queue("pat.ladder").routingKey("pat.ladder"))
                .publishers(p -> p.rate(200).messageSize(256))
                // 300ms waits with the broker threshold at 200ms: every retry goes through a rung
                // queue rather than a consumer's hold, which is the half of the ladder that costs
                // the broker something.
                .consumers(c -> c.concurrency(4).prefetch(50).failureRate(0.3)
                        .retry(RetryPolicy.fixed(3, Duration.ofMillis(300))
                                .waitInBrokerFrom(Duration.ofMillis(200))))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6))
                .expect(Objective.noMessagesLost())
                .run(amqpUrl());

        assertThat(report.isValid()).as(report.format()).isTrue();
        assertThat(report.patterns()).containsKeys("retried", "recovered", "deadLettered");
        assertThat(report.patterns().get("retried")).isPositive();
        assertThat(report.patterns().get("recovered")).isPositive();
        // Every message settles once: delivered, or dead-lettered after its third failure.
        assertThat(report.consumed()).isCloseTo(report.confirmed(), withinPercentage(1));
        assertThat(report.problems()).as(report.format()).isEmpty();
        // Three in ten messages wait at least one rung, so the tail carries the wait.
        assertThat(report.endToEnd().p99()).isGreaterThanOrEqualTo(Duration.ofMillis(300));

        try (RabbitAdmin admin = RabbitAdmin.connect("http://" + BROKER.getHost() + ":"
                + BROKER.getMappedPort(15672), "guest", "guest")) {
            assertThat(admin.queues()).extracting(QueueInfo::name)
                    .anyMatch(name -> name.startsWith("pat.ladder.retry."))
                    .contains("pat.ladder.dlq");
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("an outbox's relay publishes every write, and the latency includes the wait for it")
    void outbox() {
        WorkloadReport report = Workload.named("it-outbox")
                .topology(t -> t.exchange("pat.outbox", "direct").queue("pat.outbox.q")
                        .boundTo("pat.outbox", "k").routingKey("k"))
                .publishers(p -> p.rate(300).messageSize(512).outbox(50, Duration.ofMillis(20)))
                .consumers(c -> c.concurrency(2).prefetch(100))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6))
                .expect(Objective.noMessagesLost())
                .run(amqpUrl());

        assertThat(report.isValid()).as(report.format()).isTrue();
        assertThat(report.published()).isPositive();
        assertThat(report.confirmed()).isEqualTo(report.published());
        assertThat(report.consumed()).isCloseTo(report.confirmed(), withinPercentage(1));
        assertThat(report.patterns().get("outboxLeft")).isZero();
        assertThat(report.patterns().get("outboxPeak")).isPositive();
        assertThat(report.publishLatency().isEmpty()).isFalse();
        assertThat(report.problems()).as(report.format()).isEmpty();
    }

    @Test
    @Timeout(180)
    @DisplayName("a claim check offloads every large body, and the consumers clear the store")
    void claimCheck(@TempDir Path store) throws IOException {
        WorkloadReport report = Workload.named("it-claim")
                .topology(t -> t.queue("pat.claim").routingKey("pat.claim"))
                // A low rate on two threads: the store's write happens on the publishing thread,
                // and on a busy CI runner a file write can take a tenth of a second.
                .publishers(p -> p.threads(2).rate(5).messageSize(128 * 1024).claimCheck(64 * 1024, store))
                .consumers(c -> c.concurrency(2).prefetch(20))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6))
                .expect(Objective.noMessagesLost())
                .run(amqpUrl());

        assertThat(report.isValid()).as(report.format()).isTrue();
        assertThat(report.consumed()).isPositive();
        assertThat(report.patterns().get("claimChecked")).isEqualTo(report.consumed());
        assertThat(report.patterns().get("claimMissing")).isZero();
        assertThat(report.problems()).as(report.format()).isEmpty();
        try (Stream<Path> left = Files.list(store)) {
            // Warm-up messages are deleted as well; nothing published is left behind.
            assertThat(left).isEmpty();
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("a saga runs for every message, compensates its failures, and loses none")
    void saga() {
        WorkloadReport report = Workload.named("it-saga")
                .topology(t -> t.queue("pat.saga").routingKey("pat.saga"))
                .publishers(p -> p.rate(200).messageSize(256))
                // One step in ten fails and one compensation in five: plenty of both in a few
                // seconds, so every count has something in it.
                .consumers(c -> c.concurrency(4).prefetch(50)
                        .saga(new SagaLoad(3, Duration.ofMillis(1), 0.1, 0.2)))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6))
                .expect(Objective.noMessagesLost())
                .run(amqpUrl());

        assertThat(report.isValid()).as(report.format()).isTrue();
        assertThat(report.patterns()).containsKeys("sagaCompleted", "sagaCompensated", "sagaUnresolved");
        // Every message settles once, whatever its saga did.
        assertThat(report.patterns().get("sagaCompleted") + report.patterns().get("sagaCompensated"))
                .isEqualTo(report.consumed());
        assertThat(report.patterns().get("sagaCompensated")).isPositive();
        assertThat(report.patterns().get("sagaUnresolved")).isPositive()
                .isLessThanOrEqualTo(report.patterns().get("sagaCompensated"));
        assertThat(report.consumed()).isCloseTo(report.confirmed(), withinPercentage(1));
        assertThat(report.problems()).as(report.format()).isEmpty();
        // Three 1ms steps at the least, and the latency is read after them.
        assertThat(report.endToEnd().p50()).isGreaterThanOrEqualTo(Duration.ofMillis(3));
    }

    @Test
    @Timeout(180)
    @DisplayName("a pipeline ends every run once: completed, ended early or dead-lettered")
    void pipeline() {
        WorkloadReport report = Workload.named("it-pipeline")
                .topology(t -> t.queue("pat.pipe"))
                .publishers(p -> p.rate(200).messageSize(256))
                .consumers(c -> c.concurrency(2).prefetch(50).pipeline(
                        PipelineStage.named("validate").endRate(0.1),
                        // A retry through a rung on the middle step, and the no-policy rule (one
                        // attempt, then .dlq) on the last.
                        PipelineStage.named("enrich").handlerTime(Duration.ofMillis(2)).concurrency(4)
                                .failureRate(0.2)
                                .retry(RetryPolicy.fixed(2, Duration.ofMillis(300))
                                        .waitInBrokerFrom(Duration.ofMillis(200))),
                        PipelineStage.named("dispatch").failureRate(0.02)))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6))
                .expect(Objective.noMessagesLost())
                .run(amqpUrl());

        assertThat(report.isValid()).as(report.format()).isTrue();
        assertThat(report.patterns()).containsKeys("pipelineCompleted", "endedEarly", "deadLettered",
                "retried", "recovered");
        assertThat(report.patterns().get("pipelineCompleted") + report.patterns().get("endedEarly")
                + report.patterns().get("deadLettered")).isEqualTo(report.consumed());
        assertThat(report.patterns().get("pipelineCompleted")).isPositive();
        assertThat(report.patterns().get("endedEarly")).isPositive();
        assertThat(report.patterns().get("deadLettered")).isPositive();
        assertThat(report.patterns().get("retried")).isPositive();
        assertThat(report.patterns().get("recovered")).isPositive();
        assertThat(report.consumed()).isCloseTo(report.confirmed(), withinPercentage(1));
        assertThat(report.problems()).as(report.format()).isEmpty();
        // One in five runs that reach enrich waits a 300ms rung.
        assertThat(report.endToEnd().p99()).isGreaterThanOrEqualTo(Duration.ofMillis(300));

        try (RabbitAdmin admin = RabbitAdmin.connect("http://" + BROKER.getHost() + ":"
                + BROKER.getMappedPort(15672), "guest", "guest")) {
            assertThat(admin.queues()).extracting(QueueInfo::name)
                    .contains("pat.pipe.validate", "pat.pipe.enrich", "pat.pipe.dispatch",
                            "pat.pipe.dispatch.dlq")
                    .anyMatch(name -> name.startsWith("pat.pipe.enrich.retry."));
        }
    }
}
