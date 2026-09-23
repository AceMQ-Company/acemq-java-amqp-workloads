/*
 * Copyright the AceMQ authors.
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A queue that lives on another broker, measured against two real ones.
 *
 * <p>Two separate brokers, deliberately not a cluster. A cluster shares its queues, so a scenario
 * naming two members would be measuring one estate through two doors and every assertion here
 * would pass without the feature existing at all. Federation and shovels exist for exactly the
 * case these containers reproduce: brokers that share nothing.
 *
 * <p>What this covers that {@code LinkedQueueTest} cannot. That class asserts the setter, the
 * warning and the file format, all in memory — it never opens a connection. Everything that makes
 * the feature work is below that line: a second {@code AceMq} per distinct URL, the queue's own
 * connection used for consuming and for both depth reads and the sampler's, {@code declare()}
 * skipping a remote queue so it does not try to bind it to an exchange that is not there, and the
 * remote connections being closed at the end. None of it had ever run.
 */
@Testcontainers
@DisplayName("a queue on another broker")
class LinkedQueueIT {

    private static final Network NETWORK = Network.newNetwork();

    /** Where the producer publishes and the shovel takes from. */
    @Container
    private static final RabbitMQContainer UPSTREAM = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            .withNetwork(NETWORK)
            .withNetworkAliases("upstream")
            .withPluginsEnabled("rabbitmq_shovel", "rabbitmq_shovel_management");

    /** Where the shovel puts them, and where the scenario says one of its queues lives. */
    @Container
    private static final RabbitMQContainer DOWNSTREAM = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            .withNetwork(NETWORK)
            .withNetworkAliases("downstream");

    private static String urlOf(RabbitMQContainer broker) {
        return "amqp://guest:guest@" + broker.getHost() + ":" + broker.getAmqpPort();
    }

    @Test
    @Timeout(180)
    @DisplayName("is consumed from the broker it is on, not from the one the run started against")
    void aRemoteQueueIsConsumedFromItsOwnBroker() throws Exception {
        // The queue exists only downstream. If the run consumed it from upstream -- the broker the
        // scenario names -- it would either declare a second queue of that name there and measure
        // an empty one, or fail. Neither is visible from the report alone, which is why the
        // assertions below ask each broker separately.
        declareOn(DOWNSTREAM, "orders.moved");

        Scenario scenario = Scenario.named("linked")
                .runFor(Duration.ofSeconds(10));
        scenario.queue("orders.moved", queue -> queue
                .broker(urlOf(DOWNSTREAM))
                .consumers(group -> group.concurrency(2)));
        scenario.producer("into-downstream", producer -> producer
                .to("", "orders.moved")
                .rate(200));

        // Published to the default exchange by queue name, which routes to the queue of that name
        // on whichever broker the publish reaches. The producer has no `broker:` of its own, so
        // this is the run's own broker -- upstream -- and nothing arrives downstream by itself.
        // The shovel is what carries it, exactly as a federation link would.
        shovelFrom(UPSTREAM, "orders.moved", "orders.moved");
        declareOn(UPSTREAM, "orders.moved");

        // Sampled while it runs, not after. A consumer is attached only for the length of the run,
        // so reading the count once the report exists finds nought however well the feature works
        // -- and leaves the report as the only witness to what the report is about.
        // The shovel is itself a consumer of the upstream queue -- that is what a shovel is, and
        // what message-state.md warns about -- so "nothing consumes upstream" is not the
        // assertion. What must be true is that the run adds nothing on top of it.
        int upstreamBefore = consumerCountOn(UPSTREAM, "orders.moved");

        ScenarioHandle handle = ScenarioRunner.start(
                scenario, urlOf(UPSTREAM), null, ScenarioListener.NONE);
        int downstreamConsumers = 0;
        int upstreamConsumers = 0;
        long deadline = System.currentTimeMillis() + 20_000;
        while (handle.isRunning() && System.currentTimeMillis() < deadline) {
            int seen = consumerCountOn(DOWNSTREAM, "orders.moved");
            if (seen > 0) {
                downstreamConsumers = seen;
                upstreamConsumers = consumerCountOn(UPSTREAM, "orders.moved");
                break;
            }
            Thread.sleep(500);
        }
        ScenarioReport report = handle.report().get();

        assertThat(report.isValid()).as("the run itself").isTrue();

        // Asserted against the broker rather than against the report: the report is what the code
        // under test wrote, and it would say the same thing whichever broker it had read.
        assertThat(downstreamConsumers)
                .as("consumers attached downstream, where the queue lives")
                .isGreaterThan(0);
        assertThat(upstreamConsumers)
                .as("the run attached no consumer upstream; only the shovel is there")
                .isEqualTo(upstreamBefore);

        ScenarioReport.QueueResult measured = report.queues().stream()
                .filter(q -> q.name().equals("orders.moved"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the remote queue was not measured at all"));
        assertThat(measured.consumed())
                .as("messages consumed across the link")
                .isGreaterThan(0);
    }

    @Test
    @Timeout(180)
    @DisplayName("is not declared by the run, because the link owns it")
    void aRemoteQueueIsLeftToItsLink() throws Exception {
        // declare() skips a remote queue. The failure this prevents is not subtle in production and
        // is invisible in a unit test: binding a queue to an exchange that exists only on the other
        // side fails the run, and declaring one with different arguments from the link's is a
        // PRECONDITION_FAILED at some later moment nobody connects to this.
        String name = "orders.not.declared.here";
        declareOn(DOWNSTREAM, name);

        Scenario scenario = Scenario.named("undeclared")
                .runFor(Duration.ofSeconds(3));
        scenario.queue(name, queue -> queue
                .broker(urlOf(DOWNSTREAM))
                .consumers(group -> group.enabled(false)));
        // Publishing at the queue by name, which is the only key a scenario will accept for the
        // default exchange: a producer aimed at something that is not a queue in the file is
        // refused before the run starts, which is correct and is not what this test is about.
        scenario.producer("p", producer -> producer.to("", name).rate(1));

        ScenarioRunner.run(scenario, urlOf(UPSTREAM), null);

        assertThat(existsOn(UPSTREAM, name))
                .as("the run must not have created the remote queue on its own broker")
                .isFalse();
    }

    @Test
    @Timeout(180)
    @DisplayName("leaves no connection behind on the broker it reached across to")
    void remoteConnectionsAreClosed() throws Exception {
        declareOn(DOWNSTREAM, "orders.closing");
        int before = connectionCountOn(DOWNSTREAM);

        Scenario scenario = Scenario.named("closing").runFor(Duration.ofSeconds(5));
        scenario.queue("orders.closing", queue -> queue
                .broker(urlOf(DOWNSTREAM))
                .consumers(group -> group.concurrency(1)));
        scenario.producer("p", producer -> producer.to("", "orders.closing").rate(50));
        declareOn(UPSTREAM, "orders.closing");

        ScenarioRunner.run(scenario, urlOf(UPSTREAM), null);

        // A run that opens a connection per remote broker and never closes it leaks one per run,
        // which nothing in a single run's report can show. Polled rather than read once: the
        // broker reports a connection as open until it has finished tearing it down.
        long deadline = System.currentTimeMillis() + 30_000;
        int after = connectionCountOn(DOWNSTREAM);
        while (after > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            after = connectionCountOn(DOWNSTREAM);
        }
        assertThat(after)
                .as("connections left open on the remote broker after the run")
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ the brokers themselves

    private static void declareOn(RabbitMQContainer broker, String queue) throws Exception {
        // rabbitmqadmin v2, which is what RabbitMQ 4 ships: named flags rather than the key=value
        // pairs v1 took. The old spelling fails with "unexpected argument 'name=...'", which reads
        // like a quoting mistake rather than like a different program.
        exec(broker, "rabbitmqadmin", "declare", "queue", "--name", queue, "--type", "classic");
    }

    private static void shovelFrom(RabbitMQContainer broker, String from, String to)
            throws Exception {
        // Named on the upstream, taking from its queue and publishing to the downstream's queue of
        // the same name. This is the broker-side half that the tool deliberately does not create:
        // a link is somebody else's configuration, and the scenario only says where a queue is.
        exec(broker, "rabbitmqctl", "set_parameter", "shovel", "to-downstream",
                "{\"src-protocol\":\"amqp091\",\"src-uri\":\"amqp://\",\"src-queue\":\"" + from
                + "\",\"dest-protocol\":\"amqp091\","
                + "\"dest-uri\":\"amqp://guest:guest@downstream\",\"dest-queue\":\"" + to + "\"}");
    }

    private static int consumerCountOn(RabbitMQContainer broker, String queue) throws Exception {
        String out = exec(broker, "rabbitmqctl", "list_queues", "name", "consumers");
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals(queue)) {
                return Integer.parseInt(parts[1]);
            }
        }
        return 0;
    }

    private static boolean existsOn(RabbitMQContainer broker, String queue) throws Exception {
        return exec(broker, "rabbitmqctl", "list_queues", "name").contains(queue);
    }

    private static int connectionCountOn(RabbitMQContainer broker) throws Exception {
        String out = exec(broker, "rabbitmqctl", "list_connections", "name");
        int count = 0;
        for (String line : out.split("\n")) {
            if (line.contains("->")) {
                count++;
            }
        }
        return count;
    }

    private static String exec(RabbitMQContainer broker, String... command) throws Exception {
        var result = broker.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    String.join(" ", command) + " failed: " + result.getStderr());
        }
        return result.getStdout();
    }
}
