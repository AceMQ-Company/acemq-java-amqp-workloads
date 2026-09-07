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

import java.util.concurrent.atomic.AtomicBoolean;

import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.workloads.metrics.LatencySummary;
import org.acemq.workloads.scenario.Scenario;
import org.acemq.workloads.scenario.ScenarioListener;
import org.acemq.workloads.scenario.ScenarioReport;
import org.acemq.workloads.scenario.ScenarioRunner;
import org.acemq.workloads.scenario.ScenarioSample;

/**
 * Executes one {@link Workload}, as the scenario it is.
 *
 * <p>A workload is one exchange, one queue and one set of publishers — which is a scenario with a
 * single node of each kind. This used to be its own engine: its own publisher loop, its own open
 * loop schedule, its own sampler, its own block watcher, its own drain, four hundred and fifty
 * lines beside four hundred and fifty nearly identical ones. Two copies of a measurement engine is
 * two places for the measurement to be subtly wrong, and only one of them gets fixed — the trap
 * this project has already been caught by once, in another language.
 *
 * <p>So there is one engine, and this is the translation either side of it: a workload in, a
 * {@link WorkloadReport} out.
 *
 * <p>Not public. A run is started through {@link Workload#run(String)}.
 */
final class WorkloadRun {

    private final Workload workload;
    private final String brokerUrl;
    private final RunListener listener;
    private final AtomicBoolean stopRequested;

    WorkloadRun(Workload workload, String brokerUrl) {
        this(workload, brokerUrl, RunListener.NONE, new AtomicBoolean(false));
    }

    WorkloadRun(Workload workload, String brokerUrl, RunListener listener,
            AtomicBoolean stopRequested) {
        this.workload = workload;
        this.brokerUrl = brokerUrl;
        this.listener = listener;
        this.stopRequested = stopRequested;
    }

    WorkloadReport execute() {
        ScenarioReport report = ScenarioRunner.run(asScenario(), brokerUrl, null,
                new Adapter(listener), stopRequested);
        return translate(report);
    }

    /**
     * The workload as a one-node scenario.
     *
     * <p>The names matter only in the report the engine builds, which is then translated away, so
     * they are the workload's own: a failure that escapes with a node name in it should still read
     * as the workload it came from.
     *
     * @return the scenario to run
     */
    private Scenario asScenario() {
        TopologySpec topology = workload.topology();
        PublisherSpec publishers = workload.publishers();
        ConsumerSpec consumers = workload.consumers();

        Scenario scenario = Scenario.named(workload.name())
                .warmup(workload.warmup())
                .runFor(workload.duration());
        if (!topology.shouldDeclare()) {
            scenario.useExisting();
        }

        // The default exchange is not declared and not bound to: publishing to it with the queue's
        // name as the key is how a queue is addressed directly, and declaring an exchange called
        // "" is refused by every broker.
        if (!topology.usesDefaultExchange()) {
            scenario.exchange(topology.exchange(), topology.exchangeType());
        }

        scenario.queue(topology.queue(), queue -> {
            if (!topology.usesDefaultExchange()) {
                queue.boundTo(topology.exchange(), topology.routingKey());
            }
            queue.consumers(group -> group
                    .concurrency(consumers.concurrency())
                    .prefetch(consumers.prefetch())
                    .handlerTime(consumers.handlerTime())
                    .failureRate(consumers.failureRate())
                    .enabled(consumers.isEnabled()));
        });

        scenario.producer(workload.name(), producer -> {
            producer.to(topology.exchange(), topology.routingKey())
                    .threads(publishers.threadCount())
                    .confirms(publishers.confirms())
                    .maxInFlight(publishers.maxInFlight())
                    .maxMessages(publishers.maxMessages())
                    .payload(publishers.payload());
            if (publishers.isUnthrottled()) {
                producer.unthrottled();
            } else {
                producer.rate(publishers.rate());
            }
        });

        return scenario;
    }

    /**
     * @param report what the engine measured
     * @return the same run, said the way a workload says it
     */
    private WorkloadReport translate(ScenarioReport report) {
        ScenarioReport.ProducerResult producer = report.producers().isEmpty()
                ? null : report.producers().get(0);
        ScenarioReport.QueueResult queue = report.queues().isEmpty()
                ? null : report.queues().get(0);

        return new WorkloadReport(workload, report.startedAt(), report.duration(),
                producer == null ? 0 : producer.published(),
                producer == null ? 0 : producer.confirmed(),
                producer == null ? 0 : producer.failed(),
                queue == null ? 0 : queue.consumed(),
                queue == null ? LatencySummary.empty("end-to-end") : queue.endToEnd(),
                producer == null ? LatencySummary.empty("publish") : producer.publishLatency(),
                producer == null ? LatencySummary.empty("send lag") : producer.sendLag(),
                depthAtEnd(queue),
                report.blockedFor().toNanos(), report.blockedReason());
    }

    /**
     * The queue depth when it ended.
     *
     * <p>The engine reads this over AMQP, which is right during a run: the management API opens an
     * HTTP connection, and doing that every second would add load to the broker being measured. A
     * workload given a management URL asked for the more accurate answer at the end, though, and
     * it still gets it.
     *
     * @param queue what the engine saw
     * @return the depth, or null if it could not be had
     */
    private Long depthAtEnd(ScenarioReport.QueueResult queue) {
        if (workload.managementUrl() != null) {
            try (RabbitAdmin admin = RabbitAdmin.connect(workload.managementUrl(),
                    workload.managementUser(), workload.managementPassword())) {
                Long depth = admin.queue(workload.topology().queue())
                        .map(q -> q.messagesReady())
                        .orElse(null);
                if (depth != null) {
                    return depth;
                }
            } catch (RuntimeException e) {
                // A depth that cannot be read is one fewer line in the report, not a failed run.
            }
        }
        return queue == null ? null : queue.depthAtEnd();
    }

    /**
     * Turns the engine's per-node readings back into the workload's single-path ones.
     *
     * <p>A workload has one producer and one queue, so the sum over the nodes is that node.
     */
    private static final class Adapter implements ScenarioListener {

        private final RunListener listener;

        Adapter(RunListener listener) {
            this.listener = listener;
        }

        @Override
        public void onSample(ScenarioSample sample) {
            ScenarioSample.ProducerSample producer = sample.producers().isEmpty()
                    ? null : sample.producers().get(0);
            ScenarioSample.QueueSample queue = sample.queues().isEmpty()
                    ? null : sample.queues().get(0);

            listener.onSample(new Sample(
                    sample.at(),
                    sample.elapsed(),
                    sample.phase(),
                    producer == null ? 0 : producer.published(),
                    producer == null ? 0 : producer.confirmed(),
                    producer == null ? 0 : producer.failed(),
                    queue == null ? 0 : queue.consumed(),
                    producer == null ? 0 : producer.publishRate(),
                    queue == null ? 0 : queue.consumeRate(),
                    queue == null ? LatencySummary.empty("end-to-end") : queue.endToEnd(),
                    producer == null ? LatencySummary.empty("send lag") : producer.sendLag(),
                    queue == null ? null : queue.depth(),
                    sample.blocked()));
        }

        @Override
        public void onPhase(Sample.Phase phase) {
            listener.onPhase(phase);
        }

        // onFinished and onFailed are deliberately not forwarded: they carry a ScenarioReport, and
        // whoever started a workload is told with a WorkloadReport by Workload.start once this
        // returns. Forwarding both would tell a listener twice, in two currencies.
    }
}
