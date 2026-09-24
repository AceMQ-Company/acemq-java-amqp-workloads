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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a publish is addressed to when the file only names a queue.
 *
 * <p>The default exchange routes by queue name, so on it the routing key and the queue name are
 * the same thing. A spec that named a queue and left the key at its initialiser published to a
 * queue of that initialiser's name, which in general does not exist — and the broker discards an
 * unroutable message on the default exchange in silence. Nothing in a report says so: the
 * publishers publish, the confirms arrive, the queue stays empty and the consumers sit idle.
 */
@DisplayName("what a workload publishes to")
class TopologySpecRoutingTest {

    @Nested
    @DisplayName("on the default exchange")
    class OnTheDefaultExchange {

        @Test
        @DisplayName("a queue named on its own is also the routing key")
        void namingOnlyAQueueRoutesToIt() {
            TopologySpec spec = new TopologySpec().queue("chaos.drill");

            assertThat(spec.usesDefaultExchange()).isTrue();
            assertThat(spec.routingKey())
                    .as("the key a publish goes out with")
                    .isEqualTo("chaos.drill");
        }

        @Test
        @DisplayName("a key written down is used as written, even where it cannot route")
        void anExplicitKeyIsObeyed() {
            // Not corrected here. Someone who writes both means both, and the scenario's own
            // validation refuses the combination by name — which is a better answer than a
            // silent correction that makes the file and the run disagree.
            TopologySpec spec = new TopologySpec().queue("chaos.drill").routingKey("something.else");

            assertThat(spec.routingKey()).isEqualTo("something.else");
        }

        @Test
        @DisplayName("the untouched default still routes to the default queue")
        void theDefaultsAgreeWithEachOther() {
            TopologySpec spec = new TopologySpec();

            assertThat(spec.routingKey()).isEqualTo(spec.queue());
        }

        @Test
        @DisplayName("a dry run prints the key the run will use, not the one it will not")
        void toStringShowsTheEffectiveKey() {
            // --dry-run exists to show what is about to happen. Printing the field rather than the
            // effective key is how this stayed invisible: the output named a key the run would
            // never send.
            TopologySpec spec = new TopologySpec().queue("chaos.drill");

            assertThat(spec.toString()).contains("[chaos.drill]");
            assertThat(spec.toString()).doesNotContain("acemq.workload");
        }
    }

    @Nested
    @DisplayName("on a named exchange")
    class OnANamedExchange {

        @Test
        @DisplayName("the queue does not become the routing key")
        void theQueueIsNotTheKey() {
            // A topic exchange routes on the key and knows nothing about queue names, so following
            // the queue here would invent a binding the file never asked for.
            TopologySpec spec = new TopologySpec()
                    .exchange("orders", "topic")
                    .queue("orders.new");

            assertThat(spec.usesDefaultExchange()).isFalse();
            assertThat(spec.routingKey()).isEqualTo("acemq.workload");
        }

        @Test
        @DisplayName("boundTo sets the key it binds with")
        void boundToSetsTheKey() {
            TopologySpec spec = new TopologySpec()
                    .queue("orders.new")
                    .boundTo("orders", "order.created");

            assertThat(spec.routingKey()).isEqualTo("order.created");
        }
    }
}
