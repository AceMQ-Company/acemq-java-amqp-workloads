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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerGroup;
import org.acemq.amqp.core.DefaultPublisher;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.workloads.metrics.LatencyRecorder;
import org.acemq.workloads.metrics.LatencySummary;

/**
 * Executes one {@link Workload}.
 *
 * <p>Not public. A run is started through {@link Workload#run(String)}, which is the only order
 * of operations that produces a valid measurement: declare, warm up, reset, measure, drain.
 */
final class WorkloadRun {

    private final Workload workload;
    private final String brokerUrl;

    private final LatencyRecorder endToEnd = new LatencyRecorder("end-to-end");
    private final LatencyRecorder publishLatency = new LatencyRecorder("publish");
    private final LatencyRecorder sendLag = new LatencyRecorder("send lag");

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();

    private final AtomicBoolean measuring = new AtomicBoolean(false);
    private final AtomicLong blockedNanos = new AtomicLong();
    private final AtomicReference<String> blockedReason = new AtomicReference<>();

    private final RunListener listener;
    private final AtomicBoolean stopRequested;
    private final AtomicReference<Sample.Phase> phase =
            new AtomicReference<>(Sample.Phase.STARTING);

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
        try (AceMq broker = AceMq.connect(brokerUrl)) {
            declareTopology(broker);

            ConsumerGroup consumerGroup = startConsumers(broker);
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread blockWatcher = startBlockWatcher(broker, stop);
            Thread sampler = startSampler(broker, stop);

            try {
                List<Thread> publishers = startPublishers(broker, stop);

                // Warm-up runs the whole workload and throws the numbers away. Class loading,
                // JIT compilation, channel setup and the first collection all land here rather
                // than in the p99.
                enter(Sample.Phase.WARMUP);
                awaitOrStop(workload.warmup());
                resetMeasurements();

                Instant startedAt = Instant.now();
                long startNanos = System.nanoTime();
                measuring.set(true);
                enter(Sample.Phase.MEASURING);

                awaitOrStop(workload.duration());

                measuring.set(false);
                Duration measured = Duration.ofNanos(System.nanoTime() - startNanos);
                enter(Sample.Phase.DRAINING);
                stop.set(true);
                join(publishers);

                // Give the consumers a moment to finish what is already in flight, so the
                // consumed count is not short by whatever was mid-delivery when time ran out.
                if (consumerGroup != null) {
                    consumerGroup.drain(Duration.ofSeconds(5));
                }

                return new WorkloadReport(workload, startedAt, measured,
                        published.get(), confirmed.get(), failed.get(), consumed.get(),
                        summary(endToEnd, workload.consumers().isEnabled()),
                        publishLatency.summary(),
                        workload.publishers().isUnthrottled()
                                ? LatencySummary.empty("send lag")
                                : sendLag.summary(),
                        readQueueDepth(broker),
                        blockedNanos.get(), blockedReason.get());
            } finally {
                stop.set(true);
                blockWatcher.interrupt();
                sampler.interrupt();
                if (consumerGroup != null) {
                    consumerGroup.close();
                }
            }
        }
    }

    private void enter(Sample.Phase next) {
        phase.set(next);
        try {
            listener.onPhase(next);
        } catch (RuntimeException e) {
            // A run is an expensive thing to lose, and losing one because a progress bar threw
            // would be a poor trade.
        }
    }

    /**
     * Sleeps, unless somebody asks the run to stop.
     *
     * <p>Checked on a short tick rather than by interrupting the thread: interrupting the thread
     * that owns the connection risks tearing down the AMQP client mid-publish, and a run stopped
     * early is supposed to report what it measured rather than to abort.
     */
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
                throw new IllegalStateException("the workload was interrupted", e);
            }
        }
    }

    /**
     * Takes a reading a second, for anything watching.
     *
     * <p>Its own thread, because the publishers must not pay for it: reading a histogram and a
     * queue depth is cheap but not free, and doing it on a publishing thread would show up in the
     * numbers it is reporting on.
     */
    private Thread startSampler(AceMq broker, AtomicBoolean stop) {
        Thread sampler = new Thread(() -> {
            Instant startedAt = Instant.now();
            long previousNanos = System.nanoTime();
            long previousPublished = 0;
            long previousConsumed = 0;

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
                long publishedNow = published.get();
                long consumedNow = consumed.get();

                // Per-interval rates, not averages since the start. An average cannot show a
                // stall: it dips a little and recovers, where the interval rate goes to zero and
                // back, which is what the trough in the chart is for.
                double publishRate = seconds <= 0 ? 0 : (publishedNow - previousPublished) / seconds;
                double consumeRate = seconds <= 0 ? 0 : (consumedNow - previousConsumed) / seconds;

                previousNanos = now;
                previousPublished = publishedNow;
                previousConsumed = consumedNow;

                Sample sample = new Sample(
                        Instant.now(),
                        Duration.between(startedAt, Instant.now()),
                        phase.get(),
                        publishedNow,
                        confirmed.get(),
                        failed.get(),
                        consumedNow,
                        Math.max(0, publishRate),
                        Math.max(0, consumeRate),
                        endToEnd.summary(),
                        sendLag.summary(),
                        sampleQueueDepth(broker),
                        broker.isBlocked());

                try {
                    listener.onSample(sample);
                } catch (RuntimeException e) {
                    // As above: a listener that throws does not take the run with it.
                }
            }
        }, "workload-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    /**
     * The queue depth, if it can be had cheaply.
     *
     * <p>Only over AMQP, never through the management API: the management call opens an HTTP
     * connection, and doing that every second for the length of a run adds load to the broker
     * being measured. The final depth in the report is still read whichever way is available.
     */
    private Long sampleQueueDepth(AceMq broker) {
        try {
            return broker.messageCount(workload.topology().queue());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LatencySummary summary(LatencyRecorder recorder, boolean enabled) {
        return enabled ? recorder.summary() : LatencySummary.empty(recorder.toString());
    }

    private void declareTopology(AceMq broker) {
        TopologySpec topology = workload.topology();
        if (!topology.shouldDeclare()) {
            return;
        }
        if (!topology.usesDefaultExchange()) {
            broker.declareExchange(topology.exchange(), topology.exchangeType());
        }
        broker.declareQueue(topology.queue());
        if (!topology.usesDefaultExchange()) {
            broker.bind(topology.queue(), topology.exchange(), topology.routingKey());
        }
    }

    private ConsumerGroup startConsumers(AceMq broker) {
        ConsumerSpec spec = workload.consumers();
        if (!spec.isEnabled()) {
            return null;
        }
        long handlerNanos = spec.handlerTime().toNanos();

        return broker.consumeGroup(workload.topology().queue(), byte[].class, message -> {
            byte[] body = message.payload();
            if (measuring.get() && Payload.isWorkloadMessage(body)) {
                // The whole point of the exercise: latency from when the message was DUE,
                // not from when it was actually sent.
                endToEnd.record(System.nanoTime() - Payload.intendedSendNanos(body));
                consumed.incrementAndGet();
            }
            if (handlerNanos > 0) {
                LockSupport.parkNanos(handlerNanos);
            }
            if (spec.failureRate() > 0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < spec.failureRate()) {
                throw new IllegalStateException("simulated handler failure");
            }
        }).concurrency(spec.concurrency()).prefetch(spec.prefetch()).start();
    }

    private List<Thread> startPublishers(AceMq broker, AtomicBoolean stop) {
        PublisherSpec spec = workload.publishers();
        List<Thread> threads = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(spec.threadCount());

        // The offered rate is for the workload, so each thread takes its share. A thread's
        // schedule is offset by its index so they do not all fire on the same instant.
        long perThreadRate = spec.isUnthrottled() ? 0 : Math.max(1, spec.rate() / spec.threadCount());

        for (int i = 0; i < spec.threadCount(); i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                DefaultPublisher<byte[]> publisher = broker
                        .publisher(workload.topology().exchange(), workload.topology().routingKey(),
                                byte[].class)
                        .asBytes();
                ready.countDown();
                publishLoop(publisher, spec, perThreadRate, index, stop);
            }, "workload-publisher-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        try {
            ready.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return threads;
    }

    /**
     * The open-loop schedule.
     *
     * <p>Message <em>n</em> is due at {@code start + n * interval}, computed from the start
     * rather than from the previous send. Adding the interval to "now" after each publish is the
     * mistake that makes this a closed loop: every millisecond the broker takes pushes the whole
     * remaining schedule back, the offered rate silently drops to whatever the broker allows,
     * and the latency recorded is the broker's service time rather than the client's wait.
     */
    private void publishLoop(DefaultPublisher<byte[]> publisher, PublisherSpec spec,
            long perThreadRate, int index, AtomicBoolean stop) {
        Payload payload = spec.payload();
        long intervalNanos = perThreadRate == 0 ? 0 : 1_000_000_000L / perThreadRate;
        long start = System.nanoTime() + index;
        long sequence = 0;

        // Publishes go out asynchronously, with a window of outstanding confirms. Waiting for
        // each confirm before sending the next caps a thread at one message per network round
        // trip -- around 550 a second against a broker under 2ms away, whatever the broker can
        // actually take. The window keeps the offered rate independent of the round trip while
        // still applying back-pressure when the broker stops confirming.
        Semaphore window = new Semaphore(spec.maxInFlight());

        while (!stop.get() && sequence < spec.maxMessages()) {
            long intended = intervalNanos == 0 ? System.nanoTime() : start + sequence * intervalNanos;

            if (intervalNanos > 0) {
                long waitFor = intended - System.nanoTime();
                if (waitFor > 0) {
                    LockSupport.parkNanos(waitFor);
                }
            }

            try {
                // Blocking here is real back-pressure: the broker is not confirming fast enough
                // to keep the window open. It shows up as send lag below, which is exactly where
                // it belongs.
                window.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long actualSend = System.nanoTime();
            boolean record = measuring.get();
            if (record && intervalNanos > 0) {
                // How far behind its own schedule this publish went out. If this is large the
                // configured rate was never offered, and every other number is about the client.
                sendLag.record(actualSend - intended);
            }

            try {
                publisher.sendAsync(payload.build(intended, sequence))
                        .whenComplete((result, error) -> {
                            window.release();
                            if (!record) {
                                return;
                            }
                            if (error != null) {
                                failed.incrementAndGet();
                            } else {
                                // With confirms on this is the round trip to the broker's
                                // acknowledgement; with them off it is the handover to the
                                // socket, which is why the report says which was in force.
                                publishLatency.record(System.nanoTime() - actualSend);
                                confirmed.incrementAndGet();
                            }
                        });
                if (record) {
                    published.incrementAndGet();
                }
            } catch (RuntimeException e) {
                window.release();
                if (record) {
                    failed.incrementAndGet();
                }
            }
            sequence++;
        }

        // Let the outstanding confirms land, so the confirmed count is not short by whatever
        // was in the window when time ran out.
        try {
            window.tryAcquire(spec.maxInFlight(), 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Watches for the broker blocking publishers.
     *
     * <p>Polled rather than event-driven because the measurement wanted is "how much of the run
     * was spent blocked", and a run that begins already blocked would never see an event.
     */
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
        }, "workload-block-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private Long readQueueDepth(AceMq broker) {
        try {
            if (workload.managementUrl() != null) {
                try (RabbitAdmin admin = RabbitAdmin.connect(workload.managementUrl(),
                        workload.managementUser(), workload.managementPassword())) {
                    return admin.queue(workload.topology().queue())
                            .map(q -> q.messagesReady())
                            .orElse(null);
                }
            }
            return broker.messageCount(workload.topology().queue());
        } catch (RuntimeException e) {
            // A depth that cannot be read is one fewer line in the report, not a failed run.
            return null;
        }
    }

    private void resetMeasurements() {
        endToEnd.reset();
        publishLatency.reset();
        sendLag.reset();
        published.set(0);
        confirmed.set(0);
        failed.set(0);
        consumed.set(0);
        blockedNanos.set(0);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the workload was interrupted", e);
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
