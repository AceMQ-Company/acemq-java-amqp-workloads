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
        @DisplayName("a missing name is refused")
        void required() {
            assertThatThrownBy(() -> parse("broker: amqp://localhost"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("'name' is required");
        }
    }

    @Nested
    @DisplayName("the broker")
    class Broker {

        @Test
        @DisplayName("comes from --broker when the file names none")
        void suppliedByTheFlag() {
            // The file parses. It used to be refused here, before anything could ask the command
            // line whether it had a broker of its own.
            WorkloadFile file = parse("name: x\nrunFor: 30s\n")
                    .withBroker("amqp://guest:guest@supplied:5672");

            assertThat(file.brokerUrl(0)).isEqualTo("amqp://guest:guest@supplied:5672");
        }

        @Test
        @DisplayName("from --broker wins over the file's")
        void overridesTheFile() {
            WorkloadFile file = parse("name: x\nbroker: amqp://from-the-file\nrunFor: 30s\n")
                    .withBroker("amqp://from-the-flag");

            assertThat(file.brokerUrl(0)).isEqualTo("amqp://from-the-flag");
            assertThat(file.describe()).contains("amqp://from-the-flag");
        }

        @Test
        @DisplayName("from --broker replaces every broker in a suite")
        void overridesEveryWorkloadInASuite() {
            WorkloadFile file = parse("""
                    broker: amqp://from-the-file
                    runFor: 30s
                    workloads:
                      - { name: one }
                      - { name: two, broker: amqp://its-own }
                    """).withBroker("amqp://from-the-flag");

            assertThat(file.brokerUrl(0)).isEqualTo("amqp://from-the-flag");
            assertThat(file.brokerUrl(1)).isEqualTo("amqp://from-the-flag");
        }

        @Test
        @DisplayName("missing from the file and from the flag is still an error, and says so")
        void missingFromBoth() {
            assertThatThrownBy(() -> parse("name: pointless\nrunFor: 30s\n").withBroker(null))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("no broker to run 'pointless' against")
                    .hasMessageContaining("'broker' is missing from the file")
                    .hasMessageContaining("--broker was not given");
        }

        @Test
        @DisplayName("a blank flag is no flag at all")
        void blankFlagDoesNotCount() {
            assertThatThrownBy(() -> parse("name: pointless\nrunFor: 30s\n").withBroker("  "))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("no broker to run 'pointless' against");

            assertThat(parse("name: x\nbroker: amqp://from-the-file\n").withBroker("  ")
                    .brokerUrl(0)).isEqualTo("amqp://from-the-file");
        }

        @Test
        @DisplayName("naming one workload in a suite and not another names the one that is short")
        void namesTheWorkloadThatIsShort() {
            assertThatThrownBy(() -> parse("""
                    runFor: 30s
                    workloads:
                      - { name: has-one, broker: amqp://somewhere }
                      - { name: has-none }
                    """).withBroker(null))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("no broker to run 'has-none' against");
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

    @Nested
    @DisplayName("rules loaded from the file")
    class LoadedRules {

        private static final String WITH_RULES = """
                name: ruled
                broker: amqp://localhost:5672
                publishers: { rate: 1000 }
                rules:
                  - name: backlog-stays-small
                    metric: queueDepthAtEnd
                    above: 1000
                    severity: warning
                    because: a queue that ends deep was filling
                  - name: tail
                    metric: endToEnd.p999
                    above: 200ms
                  - name: errors
                    metric: errorRate
                    above: 0.5%
                """;

        @Test
        @DisplayName("each one becomes a rule beside the defaults")
        void loads() {
            Workload plain = parse("name: p\nbroker: amqp://x\npublishers: { rate: 1000 }\n")
                    .workloads().get(0);
            Workload ruled = parse(WITH_RULES).workloads().get(0);
            assertThat(ruled.rules()).hasSize(plain.rules().size() + 3);
        }

        @Test
        @DisplayName("a suite's top-level rules apply to every entry")
        void suite() {
            WorkloadFile file = parse(WITH_RULES.replace("name: ruled\n", "")
                    + "workloads:\n  - name: a\n  - name: b\n");
            assertThat(file.workloads()).allSatisfy(w ->
                    assertThat(w.rules().size()).isGreaterThan(7));
        }

        private void refused(String rules, String message) {
            assertThatThrownBy(() -> parse("name: r\nbroker: amqp://x\nrules:\n" + rules))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining(message);
        }

        @Test
        @DisplayName("are validated on load, each with a reason")
        void validated() {
            refused("  - { metric: published, above: 1 }\n", "'rules[0].name' is required");
            refused("  - { name: x, metric: p99, above: 1 }\n", "not a metric");
            refused("  - { name: x, metric: published }\n", "exactly one of 'above' or 'below'");
            refused("  - { name: x, metric: published, above: 1, below: 2 }\n", "exactly one");
            refused("  - { name: x, metric: endToEnd.p99, above: 50 }\n", "is not a duration");
            refused("  - { name: x, metric: published, above: lots }\n", "not a number");
            refused("  - { name: x, metric: errorRate, above: 150% }\n", "write a share");
            refused("  - { name: x, metric: published, above: 1, severity: dire }\n",
                    "Known: invalid, failed, warning, info");
            refused("  - { name: x, metric: published, above: 1, when: now }\n",
                    "unknown setting 'rules[0].when'");
            refused("  name: x\n", "must be a list");
        }
    }

    @Nested
    @DisplayName("a ramp")
    class Ramps {

        private static final String RAMPED = """
                name: ceiling
                broker: amqp://localhost:5672
                publishers: { threads: 2, rate: 500 }
                warmup: 2s
                runFor: 10s
                ramp:
                  start: 1000
                  step: 25%
                  stepFor: 20s
                  warmup: 5s
                  maxRate: 100000
                  maxErrorRate: 0.1%
                  tolerance: 0.02
                  refine: 3
                  ceilingAtLeast: 50000
                """;

        @Test
        @DisplayName("is read from the ramp block, and shown by --dry-run")
        void reads() {
            WorkloadFile file = parse(RAMPED);
            assertThat(file.ramp(0)).isNotNull();
            assertThat(file.ramp(0).start()).isEqualTo(1_000);
            assertThat(file.ramp(0).ceilingAtLeast()).isEqualTo(50_000);
            assertThat(file.ramp(0).toString())
                    .isEqualTo("Ramp{ceiling from 1000/s by 25.0% to 100000/s, 20s a step, refined 3x}");
            assertThat(file.describe()).contains("ramp        Ramp{ceiling");
        }

        @Test
        @DisplayName("defaults to the workload's rate, warm-up and duration")
        void defaults() {
            WorkloadFile file = parse(RAMPED.replaceAll("(?s)ramp:.*", "ramp: { step: 100 }\n"));
            assertThat(file.ramp(0).start()).isEqualTo(500);
            assertThat(file.ramp(0).toString()).contains("10s a step");
        }

        @Test
        @DisplayName("the example file is valid, as written")
        void example() {
            WorkloadFile file = WorkloadFile.read(java.nio.file.Path.of("../examples/17-ramp.yaml"),
                    name -> null);
            assertThat(file.ramp(0)).isNotNull();
            assertThat(file.ramp(0).toString()).contains("from 2000/s by 25.0%");
            assertThat(file.workloads().get(0).rules().size())
                    .isGreaterThan(parse(MINIMAL).workloads().get(0).rules().size());
        }

        @Test
        @DisplayName("a workload without one is a single run")
        void absent() {
            assertThat(parse(MINIMAL).ramp(0)).isNull();
        }

        @Test
        @DisplayName("--ramp lays its settings over the file's, key by key")
        void flag() {
            WorkloadFile file = parse(RAMPED).withRamp("step=500,refine=0");
            assertThat(file.ramp(0).toString()).isEqualTo(
                    "Ramp{ceiling from 1000/s by 500/s to 100000/s, 20s a step}");

            WorkloadFile fromNothing = parse(MINIMAL).withRamp("start=100,step=100,stepFor=5s");
            assertThat(fromNothing.ramp(0).toString()).contains("from 100/s by 100/s");

            assertThatThrownBy(() -> parse(MINIMAL).withRamp("start"))
                    .hasMessageContaining("key=value");
        }

        private void refused(String ramp, String message) {
            assertThatThrownBy(() -> parse("name: r\nbroker: amqp://x\nrunFor: 10s\n"
                    + "publishers: { rate: 100 }\nramp: " + ramp + "\n"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining(message);
        }

        @Test
        @DisplayName("is validated on load, each with a reason")
        void validated() {
            refused("{ start: 100 }", "'ramp.step' is required");
            refused("{ step: 0 }", "step must be positive");
            refused("{ step: 10, stepFor: 2s }", "at least 5s");
            refused("{ step: 10, stepFor: 20 }", "is not a duration");
            refused("{ step: 10, start: 1.5 }", "whole number");
            refused("{ step: 10, start: 500, maxRate: 100 }", "above its maxRate");
            refused("{ step: 10, tolerance: 2 }", "write a share");
            refused("{ step: 10, rate: 5 }", "unknown setting 'ramp.rate'");
            refused("fast", "must be a mapping");
            assertThatThrownBy(() -> parse("name: r\nbroker: amqp://x\n"
                    + "publishers: { unthrottled: true }\nramp: { step: 10 }\n"))
                    .hasMessageContaining("needs a starting rate");
            assertThatThrownBy(() -> parse("name: r\nbroker: amqp://x\nrunFor: forever\n"
                    + "publishers: { rate: 10 }\nramp: { step: 10 }\n"))
                    .hasMessageContaining("until-stopped");
        }
    }
}
