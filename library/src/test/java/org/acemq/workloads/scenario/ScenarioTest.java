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

import org.junit.jupiter.api.Test;

/** What a scenario refuses, and what it merely warns about. */
class ScenarioTest {

    @Test
    void describesAWholeTopologyRatherThanOnePath() {
        Scenario scenario = Scenario.named("fan-out")
                .exchange("orders", "topic")
                .queue("orders.shipping", q -> q.quorum().boundTo("orders", "order.*"))
                .queue("orders.audit", q -> q.classic().boundTo("orders", "#"))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(5_000))
                .consumers("orders.shipping", c -> c.concurrency(4).prefetch(200))
                .consumers("orders.audit", c -> c.concurrency(1));

        assertThat(scenario.problems()).isEmpty();
        assertThat(scenario.queues()).hasSize(2);
        assertThat(scenario.findQueue("orders.shipping").orElseThrow().type())
                .isEqualTo(QueueType.QUORUM);
        assertThat(scenario.findQueue("orders.audit").orElseThrow().consumersNode().concurrency())
                .isEqualTo(1);
    }

    // The mistake a designer makes constantly: a binding left pointing at a renamed exchange.
    // The broker's own error for this arrives as a channel closure mid-run.
    @Test
    void refusesABindingToAnExchangeNothingDeclares() {
        Scenario scenario = Scenario.named("typo")
                .exchange("orders", "topic")
                .queue("orders.shipping", q -> q.boundTo("odrers", "#"))
                .producer("p", p -> p.to("orders", "k"));

        assertThat(scenario.problems())
                .anyMatch(problem -> problem.contains("odrers"));
    }

    @Test
    void refusesAProducerAimedAtNothing() {
        Scenario scenario = Scenario.named("nowhere")
                .queue("orders")
                .producer("p", p -> p.to("missing-exchange", "k"));

        assertThat(scenario.problems())
                .anyMatch(problem -> problem.contains("missing-exchange"));
    }

    @Test
    void refusesAScenarioWithNothingSwitchedOn() {
        Scenario scenario = Scenario.named("all-off")
                .exchange("orders", "topic")
                .queue("orders.q", q -> q.boundTo("orders", "#").enabled(false))
                .producer("p", p -> p.to("orders", "k").enabled(false));

        assertThat(scenario.problems())
                .anyMatch(problem -> problem.contains("nothing is publishing"))
                .anyMatch(problem -> problem.contains("nothing is receiving"));
    }

    // A queue nobody reads is a legitimate thing to measure -- it is how you find out what a
    // backlog costs -- so it is worth saying and not worth refusing.
    @Test
    void warnsAboutAQueueNobodyConsumesRatherThanRefusingIt() {
        Scenario scenario = Scenario.named("backlog")
                .exchange("orders", "topic")
                .queue("orders.q", q -> q.boundTo("orders", "#").consumers(c -> c.none()))
                .producer("p", p -> p.to("orders", "k"));

        assertThat(scenario.problems()).isEmpty();
        assertThat(scenario.warnings())
                .anyMatch(warning -> warning.contains("nothing consumes orders.q"));
    }

    @Test
    void warnsAboutAnExchangeNothingIsBoundTo() {
        Scenario scenario = Scenario.named("orphan")
                .exchange("orders", "topic")
                .exchange("unused", "fanout")
                .queue("orders.q", q -> q.boundTo("orders", "#"))
                .producer("p", p -> p.to("orders", "k"));

        assertThat(scenario.warnings())
                .anyMatch(warning -> warning.contains("nothing is bound to exchange unused"));
    }

    @Test
    void switchingSomethingOffKeepsItsSettings() {
        Scenario scenario = Scenario.named("what-if")
                .exchange("orders", "topic")
                .queue("orders.q", q -> q.boundTo("orders", "#")
                        .consumers(c -> c.concurrency(8).prefetch(250).enabled(false)))
                .producer("p", p -> p.to("orders", "k"));

        ConsumerGroupNode consumers = scenario.findQueue("orders.q").orElseThrow().consumersNode();

        assertThat(consumers.isEnabled()).isFalse();
        // The point of a switch rather than a delete: what you were about to put back is still
        // there, prefetch and all.
        assertThat(consumers.concurrency()).isEqualTo(8);
        assertThat(consumers.prefetch()).isEqualTo(250);
    }

    @Test
    void worksOutHowManyThreadsARateNeeds() {
        Scenario scenario = Scenario.named("rates")
                .exchange("e", "topic")
                .queue("q", q -> q.boundTo("e", "#"))
                .producer("small", p -> p.to("e", "k").rate(1_000))
                .producer("large", p -> p.to("e", "k").rate(200_000))
                .producer("explicit", p -> p.to("e", "k").rate(200_000).threads(3));

        assertThat(scenario.producers().get(0).threadCount()).isEqualTo(1);
        assertThat(scenario.producers().get(1).threadCount()).isEqualTo(10);
        // Somebody who knows better is not argued with.
        assertThat(scenario.producers().get(2).threadCount()).isEqualTo(3);
    }

    @Test
    void queueArgumentsCarryTheType() {
        QueueNode quorum = new QueueNode("q").quorum().deadLetterTo("dead");
        assertThat(quorum.declaredArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", "dead");

        QueueNode stream = new QueueNode("s").stream();
        assertThat(stream.declaredArguments()).containsEntry("x-queue-type", "stream");

        // Classic writes no x-queue-type at all. Saying "classic" explicitly is legal and makes a
        // redeclaration of an existing queue fail for no reason worth failing over.
        assertThat(new QueueNode("c").classic().declaredArguments()).isEmpty();
        assertThat(new QueueNode("m").mirrored().declaredArguments()).isEmpty();
    }

    @Test
    void readsQueueTypesFromWhateverAFormCalledThem() {
        assertThat(QueueType.parse("quorum")).isEqualTo(QueueType.QUORUM);
        assertThat(QueueType.parse("Stream")).isEqualTo(QueueType.STREAM);
        assertThat(QueueType.parse("classic-mirrored")).isEqualTo(QueueType.CLASSIC_MIRRORED);
        assertThat(QueueType.parse("classic|mirrored")).isEqualTo(QueueType.CLASSIC_MIRRORED);
        assertThat(QueueType.parse(null)).isEqualTo(QueueType.CLASSIC);
    }

    @Test
    void defaultsAreARunnableScenario() {
        Scenario scenario = Scenario.named("defaults")
                .exchange("e", "topic")
                .queue("q", q -> q.boundTo("e", "#"))
                .producer("p", p -> p.to("e", "k"));

        assertThat(scenario.warmup()).isEqualTo(Duration.ofSeconds(5));
        assertThat(scenario.duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(scenario.shouldDeclare()).isTrue();
    }
}
