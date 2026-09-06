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
package org.acemq.workloads.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A whole topology under load, against a real broker.
 *
 * <p>The rates are deliberately modest: a container sharing a laptop with the generator is not a
 * capacity measurement. What is asserted is that the mechanism works — that several producers and
 * several queues are driven at once, that each is counted separately, that readings arrive while
 * it runs, and that stopping one produces a report rather than an exception.
 */
@Testcontainers
@DisplayName("a scenario against a real broker")
class ScenarioIT {

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
    @DisplayName("every queue in a fan-out is measured separately")
    void measuresEachLegOfAFanOutSeparately() {
        Scenario scenario = Scenario.named("fan-out")
                .exchange("it-orders", "topic")
                .queue("it-shipping", q -> q.boundTo("it-orders", "order.#")
                        .consumers(c -> c.concurrency(2).prefetch(50)))
                .queue("it-audit", q -> q.boundTo("it-orders", "#")
                        .consumers(c -> c.concurrency(1).prefetch(50)))
                .producer("checkout", p -> p.to("it-orders", "order.placed")
                        .rate(500).messageSize(256))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6));

        ScenarioReport report = ScenarioRunner.run(scenario, amqpUrl());

        assertThat(report.producers()).hasSize(1);
        assertThat(report.queues()).hasSize(2);

        // A fan-out delivers to both, so both legs saw traffic and each has its own numbers.
        for (ScenarioReport.QueueResult queue : report.queues()) {
            assertThat(queue.consumed())
                    .describedAs("%s consumed nothing", queue.name())
                    .isGreaterThan(0);
            assertThat(queue.endToEnd().count()).isGreaterThan(0);
        }
        assertThat(report.totalPublished()).isGreaterThan(0);
        assertThat(report.format()).contains("it-shipping", "it-audit", "checkout");
    }

    @Test
    @Timeout(180)
    @DisplayName("two producers on one exchange are told apart")
    void countsProducersSeparately() {
        Scenario scenario = Scenario.named("two-voices")
                .exchange("it-two", "topic")
                .queue("it-two-q", q -> q.boundTo("it-two", "#")
                        .consumers(c -> c.concurrency(2)))
                .producer("loud", p -> p.to("it-two", "a").rate(400))
                .producer("quiet", p -> p.to("it-two", "b").rate(50))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6));

        ScenarioReport report = ScenarioRunner.run(scenario, amqpUrl());

        ScenarioReport.ProducerResult loud = report.producers().stream()
                .filter(p -> p.name().equals("loud")).findFirst().orElseThrow();
        ScenarioReport.ProducerResult quiet = report.producers().stream()
                .filter(p -> p.name().equals("quiet")).findFirst().orElseThrow();

        // The whole point of naming producers: the difference between them is visible.
        assertThat(loud.published()).isGreaterThan(quiet.published());
    }

    @Test
    @Timeout(180)
    @DisplayName("a queue nobody consumes grows, and the report says so")
    void reportsAQueueThatIsFillingUp() {
        Scenario scenario = Scenario.named("backlog")
                .exchange("it-backlog", "topic")
                .queue("it-backlog-q", q -> q.boundTo("it-backlog", "#").consumers(c -> c.none()))
                .producer("p", p -> p.to("it-backlog", "k").rate(300))
                .warmup(Duration.ofSeconds(1))
                .runFor(Duration.ofSeconds(5));

        ScenarioReport report = ScenarioRunner.run(scenario, amqpUrl());

        ScenarioReport.QueueResult queue = report.queues().get(0);
        assertThat(queue.consumers()).isZero();
        assertThat(queue.depthAtEnd()).isNotNull();
        assertThat(queue.depthAtEnd()).isGreaterThan(0);
    }

    @Test
    @Timeout(180)
    @DisplayName("readings arrive while it runs, and stopping it produces a report")
    void reportsWhileRunningAndStopsOnRequest() {
        Scenario scenario = Scenario.named("watched")
                .exchange("it-watch", "topic")
                .queue("it-watch-q", q -> q.boundTo("it-watch", "#")
                        .consumers(c -> c.concurrency(2)))
                .producer("p", p -> p.to("it-watch", "k").rate(400))
                .warmup(Duration.ofSeconds(1))
                // Long enough that the test would time out if stop() did not work.
                .runFor(Duration.ofMinutes(5));

        List<ScenarioSample> samples = new CopyOnWriteArrayList<>();
        ScenarioHandle handle = ScenarioRunner.start(scenario, amqpUrl(), samples::add);

        // Wait for a few readings, then pull the plug.
        long deadline = System.currentTimeMillis() + 30_000;
        while (samples.size() < 4 && System.currentTimeMillis() < deadline) {
            sleep(200);
        }
        handle.stop();

        ScenarioReport report = handle.report().join();

        assertThat(samples).hasSizeGreaterThanOrEqualTo(4);
        assertThat(samples.get(samples.size() - 1).queues()).hasSize(1);
        assertThat(samples).anyMatch(s -> s.totalPublishRate() > 0);

        // A stopped run reports on the window it measured rather than throwing away the work.
        assertThat(report.wasStoppedEarly()).isTrue();
        assertThat(report.duration()).isLessThan(Duration.ofMinutes(5));
        assertThat(report.findings())
                .anyMatch(f -> f.rule().equals("run-was-stopped"));
        assertThat(report.totalPublished()).isGreaterThan(0);
    }

    @Test
    @Timeout(180)
    @DisplayName("a quorum queue and a classic queue can be compared in one run")
    void runsMoreThanOneQueueType() {
        Scenario scenario = Scenario.named("types")
                .exchange("it-types", "fanout")
                .queue("it-types-classic", q -> q.classic().boundTo("it-types", "")
                        .consumers(c -> c.concurrency(2)))
                .queue("it-types-quorum", q -> q.quorum().boundTo("it-types", "")
                        .consumers(c -> c.concurrency(2)))
                .producer("p", p -> p.to("it-types", "").rate(300))
                .warmup(Duration.ofSeconds(2))
                .runFor(Duration.ofSeconds(6));

        ScenarioReport report = ScenarioRunner.run(scenario, amqpUrl());

        assertThat(report.queues()).extracting(ScenarioReport.QueueResult::type)
                .containsExactlyInAnyOrder(QueueType.CLASSIC, QueueType.QUORUM);
        for (ScenarioReport.QueueResult queue : report.queues()) {
            assertThat(queue.consumed())
                    .describedAs("%s consumed nothing", queue.name())
                    .isGreaterThan(0);
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("the broker is asked what queue types it has")
    void readsWhatTheBrokerSupports() {
        BrokerCapabilities capabilities =
                BrokerCapabilities.of(managementUrl(), "guest", "guest");

        assertThat(capabilities.isKnown()).isTrue();
        assertThat(capabilities.version()).isNotBlank();
        assertThat(capabilities.supports(QueueType.CLASSIC)).isTrue();
        assertThat(capabilities.supports(QueueType.QUORUM)).isTrue();
        assertThat(capabilities.supports(QueueType.STREAM)).isTrue();

        // This is a 4.x broker, so mirrored classic queues are gone -- and the reason is what the
        // studio shows instead of the option.
        assertThat(capabilities.supports(QueueType.CLASSIC_MIRRORED)).isFalse();
        assertThat(capabilities.whyNot(QueueType.CLASSIC_MIRRORED))
                .contains("removed in RabbitMQ 4.0");
    }

    @Test
    @Timeout(120)
    @DisplayName("a management API that cannot be reached is a guess, and says so")
    void admitsWhenItCouldNotAskTheBroker() {
        BrokerCapabilities capabilities =
                BrokerCapabilities.of("http://localhost:1", "guest", "guest");

        assertThat(capabilities.isKnown()).isFalse();
        assertThat(capabilities.supports(QueueType.CLASSIC)).isTrue();
        assertThat(capabilities.supports(QueueType.STREAM)).isFalse();
        assertThat(capabilities.whyNot(QueueType.STREAM)).contains("could not be asked");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
