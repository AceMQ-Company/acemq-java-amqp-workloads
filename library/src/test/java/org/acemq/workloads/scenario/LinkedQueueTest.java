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

import org.junit.jupiter.api.Test;

/**
 * A queue on the far side of a federation or shovel link.
 *
 * <p>The scenario does not create the link -- that is broker configuration -- it stands on both
 * ends of one that already exists. So what these pin is that a queue somewhere else is a legal
 * scenario rather than a broken one: it needs no bindings, it is not warned about for having
 * none, and it survives a trip through the file format.
 */
class LinkedQueueTest {

    @Test
    void aQueueCanLiveOnAnotherBroker() {
        Scenario scenario = Scenario.named("federation")
                .exchange("orders", "topic")
                .queue("orders.upstream", q -> q.boundTo("orders", "#").consumers(c -> c.none()))
                .queue("orders.downstream", q -> q.broker("amqp://downstream:5672")
                        .consumers(c -> c.concurrency(2)))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(100));

        assertThat(scenario.problems()).isEmpty();
        assertThat(scenario.findQueue("orders.downstream").orElseThrow().isRemote()).isTrue();
        assertThat(scenario.findQueue("orders.downstream").orElseThrow().broker())
                .isEqualTo("amqp://downstream:5672");
        assertThat(scenario.findQueue("orders.upstream").orElseThrow().isRemote()).isFalse();
    }

    // The far end is fed by the link, not by a binding this scenario makes. Warning that nothing
    // is bound to it would be telling somebody their federation is misconfigured when it is
    // working exactly as intended.
    @Test
    void aRemoteQueueIsNotWarnedAboutForHavingNoBindings() {
        Scenario scenario = Scenario.named("federation")
                .exchange("orders", "topic")
                .queue("orders.upstream", q -> q.boundTo("orders", "#").consumers(c -> c.none()))
                .queue("orders.downstream", q -> q.broker("amqp://downstream:5672")
                        .consumers(c -> c.concurrency(2)))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(100));

        assertThat(scenario.warnings())
                .noneMatch(warning -> warning.contains("nothing is bound to orders.downstream"));
    }

    // A local queue with no bindings is still worth a warning: nothing feeds it and nobody
    // asked for a link.
    @Test
    void aLocalQueueWithNoBindingsIsStillWarnedAbout() {
        Scenario scenario = Scenario.named("unbound")
                .exchange("orders", "topic")
                .queue("orders.stranded", q -> q.consumers(c -> c.concurrency(1)))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(100));

        assertThat(scenario.warnings())
                .anyMatch(warning -> warning.contains("nothing is bound to orders.stranded"));
    }

    @Test
    void theBrokerSurvivesTheFileFormat() {
        Scenario scenario = Scenario.named("federation")
                .exchange("orders", "topic")
                .queue("orders.upstream", q -> q.boundTo("orders", "#").consumers(c -> c.none()))
                .queue("orders.downstream", q -> q.broker("amqp://downstream:5672")
                        .consumers(c -> c.concurrency(2)))
                .producer("checkout", p -> p.to("orders", "order.placed").rate(100));

        ScenarioFile file = ScenarioFile.of(scenario, "amqp://upstream:5672", null, null);
        Scenario again = file.toScenario();

        assertThat(again.findQueue("orders.downstream").orElseThrow().broker())
                .isEqualTo("amqp://downstream:5672");
        assertThat(again.findQueue("orders.upstream").orElseThrow().broker()).isNull();
    }
}
