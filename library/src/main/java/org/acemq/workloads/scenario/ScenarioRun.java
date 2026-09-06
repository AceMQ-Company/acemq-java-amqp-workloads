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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerGroup;
import org.acemq.amqp.core.DefaultPublisher;
import org.acemq.amqp.security.Security;
import org.acemq.amqp.transport.ConnectionConfig;
import org.acemq.workloads.Payload;
import org.acemq.workloads.Sample;
import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;

/**
 * Runs a {@link Scenario}: every producer, every queue, one connection, one clock.
 *
 * <p>Not public. A run is started through {@link ScenarioRunner}, which is the only order of
 * operations that produces a valid measurement: declare, start consumers, start producers, warm
 * up, reset, measure, drain.
 */
final class ScenarioRun {

    private final Scenario scenario;
    private final String brokerUrl;
    private final Security security;
    private final ScenarioListener listener;
    private final AtomicBoolean stopRequested;

    private final Map<String, ProducerCounters> producerCounters = new LinkedHashMap<>();
    private final Map<String, QueueCounters> queueCounters = new LinkedHashMap<>();

    private final AtomicBoolean measuring = new AtomicBoolean(false);
    private final AtomicLong blockedNanos = new AtomicLong();
    private final AtomicReference<String> blockedReason = new AtomicReference<>();
    private final AtomicReference<Sample.Phase> phase =
            new AtomicReference<>(Sample.Phase.STARTING);

    ScenarioRun(Scenario scenario, String brokerUrl, Security security,
            ScenarioListener listener, AtomicBoolean stopRequested) {
        this.scenario = scenario;
        this.brokerUrl = brokerUrl;
        this.security = security;
        this.listener = listener;
        this.stopRequested = stopRequested;
    }

    /** Everything one producer is counting. */
    private static final class ProducerCounters {
        final AtomicLong published = new AtomicLong();
        final AtomicLong confirmed = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final LatencyRecorder publishLatency;
        final LatencyRecorder sendLag;

        ProducerCounters(String name) {
            this.publishLatency = new LatencyRecorder(name + " publish");
            this.sendLag = new LatencyRecorder(name + " send lag");
        }

        void reset() {
            published.set(0);
            confirmed.set(0);
            failed.set(0);
            publishLatency.reset();
            sendLag.reset();
        }
    }

    /** Everything one queue is counting. */
    private static final class QueueCounters {
        final AtomicLong consumed = new AtomicLong();
        final LatencyRecorder endToEnd;
        volatile Long depthAtStart;

        QueueCounters(String name) {
            this.endToEnd = new LatencyRecorder(name + " end-to-end");
        }

        void reset() {
            consumed.set(0);
            endToEnd.reset();
        }
    }

    ScenarioReport execute() {
        List<String> problems = scenario.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "this scenario cannot run:\n  - " + String.join("\n  - ", problems));
        }

        for (ProducerNode producer : scenario.activeProducers()) {
            producerCounters.put(producer.name(), new ProducerCounters(producer.name()));
        }
        for (QueueNode queue : scenario.activeQueues()) {
            queueCounters.put(queue.name(), new QueueCounters(queue.name()));
        }

        try (AceMq broker = connect()) {
            declare(broker);

            List<ConsumerGroup> groups = startConsumers(broker);
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread blockWatcher = startBlockWatcher(broker, stop);
            Thread sampler = startSampler(broker, stop);

            try {
                List<Thread> publishers = startProducers(broker, stop);

                enter(Sample.Phase.WARMUP);
                awaitOrStop(scenario.warmup());

                for (ProducerCounters counters : producerCounters.values()) {
                    counters.reset();
                }
                for (Map.Entry<String, QueueCounters> entry : queueCounters.entrySet()) {
                    entry.getValue().reset();
                    // Read before measuring so the report can say whether a queue grew, which is
                    // the difference between "the consumers kept up" and "they nearly did".
                    entry.getValue().depthAtStart = depthOf(broker, entry.getKey());
                }

                Instant startedAt = Instant.now();
                long startNanos = System.nanoTime();
                measuring.set(true);
                enter(Sample.Phase.MEASURING);

                awaitOrStop(scenario.duration());

                measuring.set(false);
                Duration measured = Duration.ofNanos(System.nanoTime() - startNanos);
                enter(Sample.Phase.DRAINING);
                stop.set(true);
                join(publishers);

                for (ConsumerGroup group : groups) {
                    group.drain(Duration.ofSeconds(5));
                }

                return report(broker, startedAt, measured);
            } finally {
                stop.set(true);
                blockWatcher.interrupt();
                sampler.interrupt();
                for (ConsumerGroup group : groups) {
                    group.close();
                }
            }
        }
    }

    /**
     * Opens the connection, with TLS when a policy was given.
     *
     * <p>Without one the URL decides, which means plaintext for amqp:// and the JVM's own trust
     * store for amqps://. A policy is what carries a private CA or a client certificate, and
     * neither can be expressed in a URL.
     */
    private AceMq connect() {
        if (security == null) {
            return AceMq.connect(brokerUrl);
        }
        return AceMq.connect(ConnectionConfig.url(brokerUrl).security(security).build());
    }

    private ScenarioReport report(AceMq broker, Instant startedAt, Duration measured) {
        List<ScenarioReport.ProducerResult> producerResults = new ArrayList<>();
        for (ProducerNode producer : scenario.activeProducers()) {
            ProducerCounters counters = producerCounters.get(producer.name());
            producerResults.add(new ScenarioReport.ProducerResult(
                    producer.name(),
                    producer.rate(),
                    counters.published.get(),
                    counters.confirmed.get(),
                    counters.failed.get(),
                    counters.publishLatency.summary(),
                    producer.isUnthrottled()
                            ? LatencySummary.empty(producer.name() + " send lag")
                            : counters.sendLag.summary(),
                    producer.expectations()));
        }

        List<ScenarioReport.QueueResult> queueResults = new ArrayList<>();
        for (QueueNode queue : scenario.activeQueues()) {
            QueueCounters counters = queueCounters.get(queue.name());
            queueResults.add(new ScenarioReport.QueueResult(
                    queue.name(),
                    queue.type(),
                    queue.consumersNode().isEnabled() ? queue.consumersNode().concurrency() : 0,
                    counters.consumed.get(),
                    counters.endToEnd.summary(),
                    counters.depthAtStart,
                    depthOf(broker, queue.name()),
                    queue.expectations()));
        }

        return new ScenarioReport(scenario, startedAt, measured, producerResults, queueResults,
                blockedNanos.get(), blockedReason.get(), stopRequested.get());
    }

    private void declare(AceMq broker) {
        if (!scenario.shouldDeclare()) {
            return;
        }
        for (ExchangeNode exchange : scenario.activeExchanges()) {
            broker.declareExchange(exchange.name(), exchange.type());
        }
        for (QueueNode queue : scenario.activeQueues()) {
            broker.declareQueue(queue.name(), transportType(queue.type()),
                    queue.declaredArguments());
            for (Binding binding : queue.bindings()) {
                broker.bind(queue.name(), binding.exchange(), binding.routingKey());
            }
        }
    }

    /**
     * The transport's idea of a queue type.
     *
     * <p>A mirrored classic queue is a classic queue: the mirroring is a policy, applied through
     * the management API, and nothing about the declaration says so. Anything else would have the
     * declaration refused.
     */
    private static org.acemq.amqp.transport.QueueType transportType(QueueType type) {
        return switch (type) {
            case QUORUM -> org.acemq.amqp.transport.QueueType.QUORUM;
            case STREAM -> org.acemq.amqp.transport.QueueType.STREAM;
            case CLASSIC, CLASSIC_MIRRORED -> org.acemq.amqp.transport.QueueType.CLASSIC;
        };
    }

    private List<ConsumerGroup> startConsumers(AceMq broker) {
        List<ConsumerGroup> groups = new ArrayList<>();
        for (QueueNode queue : scenario.activeQueues()) {
            ConsumerGroupNode spec = queue.consumersNode();
            if (!spec.isEnabled()) {
                continue;
            }
            QueueCounters counters = queueCounters.get(queue.name());
            long handlerNanos = spec.handlerTime().toNanos();
            double failureRate = spec.failureRate();

            groups.add(broker.consumeGroup(queue.name(), byte[].class, message -> {
                byte[] body = message.payload();
                if (measuring.get() && Payload.isWorkloadMessage(body)) {
                    // Latency from when the message was due, not from when it was sent. The
                    // difference is the whole argument of this library.
                    counters.endToEnd.record(System.nanoTime() - Payload.intendedSendNanos(body));
                    counters.consumed.incrementAndGet();
                }
                if (handlerNanos > 0) {
                    LockSupport.parkNanos(handlerNanos);
                }
                if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
                    throw new IllegalStateException("simulated handler failure");
                }
            }).concurrency(spec.concurrency()).prefetch(spec.prefetch()).start());
        }
        return groups;
    }

    private List<Thread> startProducers(AceMq broker, AtomicBoolean stop) {
        List<Thread> threads = new ArrayList<>();
        int total = scenario.activeProducers().stream()
                .mapToInt(ProducerNode::threadCount).sum();
        CountDownLatch ready = new CountDownLatch(total);

        for (ProducerNode producer : scenario.activeProducers()) {
            ProducerCounters counters = producerCounters.get(producer.name());
            int threadCount = producer.threadCount();
            // The rate belongs to the producer, so its threads share it out between them.
            long perThreadRate = producer.isUnthrottled()
                    ? 0
                    : Math.max(1, producer.rate() / threadCount);

            for (int i = 0; i < threadCount; i++) {
                int index = i;
                Thread thread = new Thread(() -> {
                    // A publisher per key, built once. Building one per message would put channel
                    // setup inside the measurement.
                    Map<String, DefaultPublisher<byte[]>> publishers = new LinkedHashMap<>();
                    for (String key : producer.routingKeys()) {
                        publishers.put(key, broker
                                .publisher(producer.exchange(), key, byte[].class).asBytes());
                    }
                    ready.countDown();
                    publishLoop(producer, counters, List.copyOf(publishers.values()),
                            perThreadRate, index, stop);
                }, "scenario-" + producer.name() + "-" + i);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }

        try {
            ready.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return threads;
    }

    /**
     * The open-loop schedule, one producer's share of it.
     *
     * <p>Message <em>n</em> is due at {@code start + n * interval}, computed from the start rather
     * than from the previous send. Adding the interval to "now" after each publish is the mistake
     * that turns this into a closed loop: every millisecond the broker takes pushes the schedule
     * back, the offered rate quietly becomes whatever the broker allows, and the latency recorded
     * is the broker's service time rather than the client's wait.
     */
    private void publishLoop(ProducerNode producer, ProducerCounters counters,
            List<DefaultPublisher<byte[]>> publishers, long perThreadRate, int index,
            AtomicBoolean stop) {
        Payload payload = Payload.ofBytes(producer.messageSize());
        long intervalNanos = perThreadRate == 0 ? 0 : 1_000_000_000L / perThreadRate;
        long start = System.nanoTime() + index;
        long sequence = 0;
        Semaphore window = new Semaphore(producer.maxInFlight());

        while (!stop.get() && sequence < producer.maxMessages()) {
            long intended = intervalNanos == 0 ? System.nanoTime() : start + sequence * intervalNanos;

            if (intervalNanos > 0) {
                long waitFor = intended - System.nanoTime();
                if (waitFor > 0) {
                    LockSupport.parkNanos(waitFor);
                }
            }

            try {
                window.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long actualSend = System.nanoTime();
            boolean record = measuring.get();
            if (record && intervalNanos > 0) {
                counters.sendLag.record(actualSend - intended);
            }

            // Keys are used in turn, so a producer on a topic exchange exercises every binding
            // rather than hammering the first one.
            DefaultPublisher<byte[]> publisher =
                    publishers.get((int) (sequence % publishers.size()));

            try {
                publisher.sendAsync(payload.build(intended, sequence))
                        .whenComplete((result, error) -> {
                            window.release();
                            if (!record) {
                                return;
                            }
                            if (error != null) {
                                counters.failed.incrementAndGet();
                            } else {
                                counters.publishLatency.record(System.nanoTime() - actualSend);
                                counters.confirmed.incrementAndGet();
                            }
                        });
                if (record) {
                    counters.published.incrementAndGet();
                }
            } catch (RuntimeException e) {
                window.release();
                if (record) {
                    counters.failed.incrementAndGet();
                }
            }
            sequence++;
        }

        try {
            window.tryAcquire(producer.maxInFlight(), 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread startSampler(AceMq broker, AtomicBoolean stop) {
        Thread sampler = new Thread(() -> {
            Instant startedAt = Instant.now();
            long previousNanos = System.nanoTime();
            Map<String, Long> previousPublished = new LinkedHashMap<>();
            Map<String, Long> previousConsumed = new LinkedHashMap<>();

            while (!stop.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stop.get()) {
                    return;
                }

                long now = System.nanoTime();
                double seconds = (now - previousNanos) / 1_000_000_000.0;
                previousNanos = now;

                List<ScenarioSample.ProducerSample> producerSamples = new ArrayList<>();
                for (ProducerNode producer : scenario.activeProducers()) {
                    ProducerCounters counters = producerCounters.get(producer.name());
                    long published = counters.published.get();
                    long previous = previousPublished.getOrDefault(producer.name(), 0L);
                    previousPublished.put(producer.name(), published);
                    producerSamples.add(new ScenarioSample.ProducerSample(
                            producer.name(), published, counters.confirmed.get(),
                            counters.failed.get(),
                            seconds <= 0 ? 0 : Math.max(0, (published - previous) / seconds),
                            counters.sendLag.summary()));
                }

                List<ScenarioSample.QueueSample> queueSamples = new ArrayList<>();
                for (QueueNode queue : scenario.activeQueues()) {
                    QueueCounters counters = queueCounters.get(queue.name());
                    long consumed = counters.consumed.get();
                    long previous = previousConsumed.getOrDefault(queue.name(), 0L);
                    previousConsumed.put(queue.name(), consumed);
                    queueSamples.add(new ScenarioSample.QueueSample(
                            queue.name(), consumed,
                            seconds <= 0 ? 0 : Math.max(0, (consumed - previous) / seconds),
                            depthOf(broker, queue.name()),
                            counters.endToEnd.summary(),
                            queue.consumersNode().isEnabled()));
                }

                ScenarioSample sample = new ScenarioSample(Instant.now(),
                        Duration.between(startedAt, Instant.now()), phase.get(),
                        producerSamples, queueSamples, broker.isBlocked());
                try {
                    listener.onSample(sample);
                } catch (RuntimeException e) {
                    // A run is expensive; losing one because a chart threw would be a poor trade.
                }
            }
        }, "scenario-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    private Thread startBlockWatcher(AceMq broker, AtomicBoolean stop) {
        Thread watcher = new Thread(() -> {
            long lastSeen = System.nanoTime();
            while (!stop.get()) {
                long now = System.nanoTime();
                if (broker.isBlocked()) {
                    if (measuring.get()) {
                        blockedNanos.addAndGet(now - lastSeen);
                    }
                    broker.blockedReason().ifPresent(blockedReason::set);
                }
                lastSeen = now;
                LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            }
        }, "scenario-block-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    /**
     * How deep a queue is, over AMQP only.
     *
     * <p>Never through the management API while a run is going: that is an HTTP request per queue
     * per second, against the broker being measured.
     */
    private Long depthOf(AceMq broker, String queue) {
        try {
            return broker.messageCount(queue);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void enter(Sample.Phase next) {
        phase.set(next);
        try {
            listener.onPhase(next);
        } catch (RuntimeException e) {
            // As above.
        }
    }

    private void awaitOrStop(Duration duration) {
        long deadline = System.nanoTime() + Math.max(0, duration.toNanos());
        while (System.nanoTime() < deadline) {
            if (stopRequested.get()) {
                return;
            }
            long remaining = deadline - System.nanoTime();
            try {
                Thread.sleep(Math.min(50, Math.max(1, remaining / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the scenario was interrupted", e);
            }
        }
    }

    private static void join(List<Thread> threads) {
        for (Thread thread : threads) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
