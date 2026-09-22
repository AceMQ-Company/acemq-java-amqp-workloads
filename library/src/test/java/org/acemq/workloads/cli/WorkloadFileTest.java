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

        @Test
        @DisplayName("the queue type and arguments are carried, not just recognised")
        void queueTypeAndArguments() {
            // These were read here and dropped before the broker saw them, which made a suite
            // like the one above compare classic against classic. WorkloadIT is where the
            // broker is asked; this is where the value is checked to have survived the parse.
            WorkloadFile file = parse("""
                    name: typed
                    broker: amqp://localhost:5672
                    topology:
                      queue: q.typed
                      queueType: quorum
                      arguments:
                        x-max-length: 100000
                        x-overflow: reject-publish
                    runFor: 30s
                    """);

            assertThat(file.workloads().get(0).topology().queueType()).isEqualTo("quorum");
            assertThat(file.workloads().get(0).topology().queueArguments())
                    .containsEntry("x-max-length", 100_000)
                    .containsEntry("x-overflow", "reject-publish");
        }

        @Test
        @DisplayName("an incompressible payload can be asked for without restating the size")
        void randomPayloadWithoutMessageSize() {
            // randomPayload used to be read only inside the messageSize branch, so on its own it
            // did nothing and the run went out with a kilobyte of zeroes -- the payload anything
            // that compresses is best at, and therefore the one that flatters a broker most.
            WorkloadFile file = parse("""
                    name: random
                    broker: amqp://localhost:5672
                    publishers:
                      randomPayload: true
                    runFor: 30s
                    """);

            assertThat(file.workloads().get(0).publishers().payload().isRandom()).isTrue();
            assertThat(file.workloads().get(0).publishers().payload().size()).isEqualTo(1024);
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
        @DisplayName("a queue type a workload cannot express is refused, not downgraded")
        void unsupportedQueueType() {
            // "stream" used to fall through to classic in silence, so a file asking for an
            // append-only log got a queue that deletes what it delivers.
            assertThatThrownBy(() -> parse("""
                    name: streaming
                    broker: amqp://localhost
                    topology: { queue: q, queueType: stream }
                    """))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("'topology.queueType' is 'stream'")
                    .hasMessageContaining("write a scenario file");
        }

        @Test
        @DisplayName("an exchange type with no exchange to put it on is refused")
        void exchangeTypeWithoutExchange() {
            // Without an exchange the publish goes through the default one, which has no type.
            // Accepting the key and ignoring it made "fanout" and "direct to the queue" look
            // like the same run.
            assertThatThrownBy(() -> parse("""
                    name: fanning-out
                    broker: amqp://localhost
                    topology: { queue: q, exchangeType: fanout }
                    """))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("'topology.exchangeType' was given without")
                    .hasMessageContaining("the type would be ignored");
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
        @DisplayName("an unset variable in a comment does not stop the run")
        void unsetInCommentIsNotAnError() {
            // The fault this pins: a workload file explaining its own syntax to the next reader
            // could not write the syntax down, because the placeholder in the comment was read
            // as a real one. The comment never reaches the parser, so nothing in it has any
            // business deciding whether the run happens.
            String yaml = """
                    # Export ${BROKER_PASSWORD} before running this.
                    name: documented
                    broker: amqp://guest:guest@localhost:5672
                    runFor: 30s
                    """;

            assertThat(WorkloadFile.substitute(yaml, name -> null, true))
                    .contains("${BROKER_PASSWORD}");
            assertThat(WorkloadFile.parse(yaml, false, name -> null).brokerUrl(0))
                    .isEqualTo("amqp://guest:guest@localhost:5672");
        }

        @Test
        @DisplayName("a set variable in a comment is left as written")
        void setInCommentIsStillLeftAlone() {
            // Resolving it would put the password into the comment, and the comment is the part
            // of the file somebody pastes into a ticket.
            Map<String, String> env = Map.of("BROKER_PASSWORD", "s3cret");

            assertThat(WorkloadFile.substitute("# use ${BROKER_PASSWORD}\nname: n\n", env::get, true))
                    .contains("# use ${BROKER_PASSWORD}")
                    .doesNotContain("s3cret");
        }

        @Test
        @DisplayName("a trailing comment does not stop the line it follows being resolved")
        void trailingComment() {
            Map<String, String> env = Map.of("BROKER_PASSWORD", "s3cret");

            assertThat(WorkloadFile.substitute(
                    "broker: amqp://guest:${BROKER_PASSWORD}@h  # or ${OTHER_ONE}\n", env::get, true))
                    .isEqualTo("broker: amqp://guest:s3cret@h  # or ${OTHER_ONE}\n");
        }

        @Test
        @DisplayName("in JSON a hash is never a comment")
        void jsonHasNoComments() {
            // JSON cannot carry a comment, so a # is a character in a string and nothing else.
            Map<String, String> env = Map.of("BROKER_PASSWORD", "s3cret");

            assertThat(WorkloadFile.substitute("{\"broker\": \"# ${BROKER_PASSWORD}\"}", env::get, false))
                    .contains("s3cret");
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
