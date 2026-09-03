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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.acemq.workloads.Workload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("the workload file")
class WorkloadFileTest {

    private static final String MINIMAL = """
            name: basic
            broker: amqp://localhost:5672
            topology:
              exchange: orders
              queue: orders.new
              routingKey: order.created
            publishers:
              threads: 4
              rate: 50000
              messageSize: 1024
            consumers:
              concurrency: 8
              prefetch: 100
              handlerTime: 1ms
            warmup: 10s
            runFor: 2m
            expect:
              throughputAtLeast: 45000
              p99Below: 50ms
            """;

    private static WorkloadFile parse(String yaml) {
        return WorkloadFile.parse(yaml, false, name -> null);
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads a whole workload out of YAML")
        void readsYaml() {
            WorkloadFile file = parse(MINIMAL);

            assertThat(file.size()).isEqualTo(1);
            assertThat(file.brokerUrl(0)).isEqualTo("amqp://localhost:5672");

            Workload workload = file.workloads().get(0);
            assertThat(workload.name()).isEqualTo("basic");
            assertThat(workload.topology().exchange()).isEqualTo("orders");
            assertThat(workload.topology().queue()).isEqualTo("orders.new");
            assertThat(workload.publishers().threadCount()).isEqualTo(4);
            assertThat(workload.publishers().rate()).isEqualTo(50_000);
            assertThat(workload.publishers().payload().size()).isEqualTo(1024);
            assertThat(workload.consumers().concurrency()).isEqualTo(8);
            assertThat(workload.consumers().handlerTime()).isEqualTo(Duration.ofMillis(1));
            assertThat(workload.warmup()).isEqualTo(Duration.ofSeconds(10));
            assertThat(workload.duration()).isEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("reads the same document as JSON")
        void readsJson() {
            String json = """
                    {"name":"basic","broker":"amqp://localhost:5672",
                     "publishers":{"rate":1000},"runFor":"30s"}
                    """;

            WorkloadFile file = WorkloadFile.parse(json, true, name -> null);

            assertThat(file.workloads().get(0).publishers().rate()).isEqualTo(1000);
        }

        @Test
        @DisplayName("a suite shares the top level, so the broker is written once")
        void suite() {
            WorkloadFile file = parse("""
                    broker: amqp://localhost:5672
                    runFor: 30s
                    publishers:
                      rate: 10000
                    workloads:
                      - name: classic
                        topology: { queue: q.classic, queueType: classic }
                      - name: quorum
                        topology: { queue: q.quorum, queueType: quorum }
                    """);

            assertThat(file.size()).isEqualTo(2);
            assertThat(file.brokerUrl(0)).isEqualTo("amqp://localhost:5672");
            assertThat(file.brokerUrl(1)).isEqualTo("amqp://localhost:5672");
            assertThat(file.workloads().get(0).name()).isEqualTo("classic");
            assertThat(file.workloads().get(1).topology().queueType()).isEqualTo("quorum");
            // Inherited rather than repeated.
            assertThat(file.workloads().get(1).publishers().rate()).isEqualTo(10_000);
        }
    }

    @Nested
    @DisplayName("refusing what would silently do the wrong thing")
    class Refusing {

        @Test
        @DisplayName("a misspelled setting is an error, not a default")
        void unknownKey() {
            // "prefech: 500" ignored would run at the default prefetch and report a perfectly
            // normal-looking result for a different configuration.
            assertThatThrownBy(() -> parse("""
                    name: typo
                    broker: amqp://localhost
                    consumers:
                      prefech: 500
                    """))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("unknown setting 'consumers.prefech'")
                    .hasMessageContaining("would run with the default");
        }

        @Test
        @DisplayName("a bare number is not a duration")
        void bareNumberDuration() {
            assertThatThrownBy(() -> parse("""
                    name: ambiguous
                    broker: amqp://localhost
                    runFor: 60
                    """))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("is not a duration");
        }

        @Test
        @DisplayName("a missing name or broker is refused")
        void required() {
            assertThatThrownBy(() -> parse("broker: amqp://localhost"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("'name' is required");

            assertThatThrownBy(() -> parse("name: x"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("'broker' is required");
        }
    }

    @Nested
    @DisplayName("secrets")
    class Secrets {

        @Test
        @DisplayName("an environment variable is substituted")
        void substitutes() {
            Map<String, String> env = Map.of("BROKER_PASSWORD", "s3cret");

            WorkloadFile file = WorkloadFile.parse("""
                    name: x
                    broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
                    runFor: 30s
                    """, false, env::get);

            assertThat(file.brokerUrl(0)).isEqualTo("amqp://guest:s3cret@localhost:5672");
        }

        @Test
        @DisplayName("a default may be given")
        void defaultValue() {
            WorkloadFile file = WorkloadFile.parse("""
                    name: x
                    broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5672
                    runFor: 30s
                    """, false, name -> null);

            assertThat(file.brokerUrl(0)).contains("guest:guest@");
        }

        @Test
        @DisplayName("an unset variable with no default is an error rather than an empty password")
        void unsetVariable() {
            assertThatThrownBy(() -> parse("""
                    name: x
                    broker: amqp://guest:${NOT_SET}@localhost
                    runFor: 30s
                    """))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("${NOT_SET}")
                    .hasMessageContaining("Secrets belong in the environment");
        }

        @Test
        @DisplayName("a dry run does not print the password")
        void dryRunRedacts() {
            WorkloadFile file = WorkloadFile.parse("""
                    name: x
                    broker: amqp://guest:s3cret@localhost:5672
                    runFor: 30s
                    """, false, name -> null);

            assertThat(file.describe())
                    .doesNotContain("s3cret")
                    .contains("guest:***@");
        }
    }

    @Nested
    @DisplayName("durations")
    class Durations_ {

        @Test
        @DisplayName("the units people actually write")
        void units() {
            assertThat(Durations.parse("250ms", "x")).isEqualTo(Duration.ofMillis(250));
            assertThat(Durations.parse("30s", "x")).isEqualTo(Duration.ofSeconds(30));
            assertThat(Durations.parse("5m", "x")).isEqualTo(Duration.ofMinutes(5));
            assertThat(Durations.parse("2h", "x")).isEqualTo(Duration.ofHours(2));
            assertThat(Durations.parse("500us", "x")).isEqualTo(Duration.ofNanos(500_000));
        }

        @Test
        @DisplayName("round trips through format")
        void roundTrip() {
            for (String text : new String[] {"250ms", "30s", "5m", "2h", "500us"}) {
                assertThat(Durations.format(Durations.parse(text, "x"))).isEqualTo(text);
            }
        }
    }
}
