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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.acemq.workloads.rules.Rule;
import org.acemq.workloads.rules.Rules;

/**
 * A load test, described.
 *
 * <pre>{@code
 * WorkloadReport report = Workload.named("orders-peak")
 *         .topology(t -> t
 *                 .exchange("orders", "topic")
 *                 .queue("orders.new")
 *                 .boundTo("orders", "order.created"))
 *         .publishers(p -> p
 *                 .threads(4)
 *                 .rate(50_000)
 *                 .messageSize(1024))
 *         .consumers(c -> c
 *                 .concurrency(8)
 *                 .prefetch(100)
 *                 .handlerTime(Duration.ofMillis(1)))
 *         .warmup(Duration.ofSeconds(10))
 *         .runFor(Duration.ofMinutes(2))
 *         .expect(Objective.throughputAtLeast(45_000))
 *         .expect(Objective.p99Below(Duration.ofMillis(50)))
 *         .run("amqp://localhost");
 *
 * System.out.println(report.format());
 * }</pre>
 *
 * <h2>Three things kept apart</h2>
 *
 * <p><strong>Topology</strong> is what the messages travel through. <strong>Publishers and
 * consumers</strong> are the shape of the load. <strong>Objectives</strong> are what the result
 * has to satisfy. They change independently — the same topology under ten load profiles, or the
 * same load against a classic queue and a quorum queue — and a DSL that chained them together
 * would force all three to be rewritten to vary one.
 *
 * <h2>Warm-up is not measured</h2>
 *
 * <p>{@link Builder#warmup(Duration)} runs the workload and throws the measurements away. The first
 * seconds of any JVM workload measure class loading, JIT compilation, connection and channel
 * setup and the first garbage collection, none of which the broker is responsible for. Without a
 * warm-up those costs land in the p99 and stay there.
 */
public final class Workload {

    private final String name;
    private final TopologySpec topology;
    private final PublisherSpec publishers;
    private final ConsumerSpec consumers;
    private final Duration warmup;
    private final Duration duration;
    private final List<Rule> rules;
    private final String managementUrl;
    private final String managementUser;
    private final String managementPassword;

    private Workload(Builder builder) {
        this.name = builder.name;
        this.topology = builder.topology;
        this.publishers = builder.publishers;
        this.consumers = builder.consumers;
        this.warmup = builder.warmup;
        this.duration = builder.duration;
        this.rules = List.copyOf(builder.rules);
        this.managementUrl = builder.managementUrl;
        this.managementUser = builder.managementUser;
        this.managementPassword = builder.managementPassword;
    }

    /**
     * @param name what to call this workload, in the report
     * @return a builder
     */
    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public TopologySpec topology() {
        return topology;
    }

    public PublisherSpec publishers() {
        return publishers;
    }

    public ConsumerSpec consumers() {
        return consumers;
    }

    public Duration warmup() {
        return warmup;
    }

    public Duration duration() {
        return duration;
    }

    public List<Rule> rules() {
        return rules;
    }

    String managementUrl() {
        return managementUrl;
    }

    String managementUser() {
        return managementUser;
    }

    String managementPassword() {
        return managementPassword;
    }

    /**
     * Runs it.
     *
     * @param brokerUrl an AMQP URL, for example {@code amqp://guest:guest@localhost:5672}
     * @return what happened
     */
    public WorkloadReport run(String brokerUrl) {
        return new WorkloadRun(this, brokerUrl).execute();
    }

    /**
     * Starts it on a thread of its own, and reports as it goes.
     *
     * <p>For anything that cannot block for the length of a run: a user interface drawing it live,
     * a scheduler, a test that stops the run once it has seen enough.
     *
     * <pre>{@code
     * RunHandle run = workload.start("amqp://localhost", System.out::println);
     * // ... and later
     * run.stop();
     * WorkloadReport report = run.report().join();
     * }</pre>
     *
     * @param brokerUrl an AMQP URL
     * @param listener told about each reading, or {@link RunListener#NONE}
     * @return a handle on the run
     */
    public RunHandle start(String brokerUrl, RunListener listener) {
        java.util.Objects.requireNonNull(brokerUrl, "brokerUrl");
        java.util.Objects.requireNonNull(listener, "listener");

        java.util.concurrent.atomic.AtomicBoolean stopRequested =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CompletableFuture<WorkloadReport> report =
                new java.util.concurrent.CompletableFuture<>();

        Thread thread = new Thread(() -> {
            try {
                WorkloadReport result =
                        new WorkloadRun(this, brokerUrl, listener, stopRequested).execute();
                // The listener is told before the future completes, so anything watching sees the
                // readings and the report in the order they happened.
                try {
                    listener.onFinished(result);
                } catch (RuntimeException e) {
                    // Its problem, not the run's.
                }
                report.complete(result);
            } catch (Throwable failure) {
                try {
                    listener.onFailed(failure);
                } catch (RuntimeException e) {
                    // As above.
                }
                report.completeExceptionally(failure);
            }
        }, "workload-" + name);
        // Not a daemon: a run holds a connection and a broker's queues, and letting the JVM exit
        // through the middle of one leaves the broker holding the mess.
        thread.setDaemon(false);
        thread.start();

        return new RunHandle(this, report, stopRequested);
    }

    @Override
    public String toString() {
        return "Workload{" + name + ", " + topology + ", " + publishers + ", " + consumers
                + ", for " + duration + "}";
    }

    /** Builds a {@link Workload}. */
    public static final class Builder {

        private final String name;
        private final TopologySpec topology = new TopologySpec();
        private final PublisherSpec publishers = new PublisherSpec();
        private final ConsumerSpec consumers = new ConsumerSpec();
        private Duration warmup = Duration.ofSeconds(10);
        private Duration duration = Duration.ofSeconds(60);
        private final List<Rule> rules = new ArrayList<>(Rules.defaults());
        private String managementUrl;
        private String managementUser = "guest";
        private String managementPassword = "guest";

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a workload needs a name: it identifies the run");
            }
            this.name = name;
        }

        /**
         * @param spec what the messages travel through
         * @return this builder
         */
        public Builder topology(Consumer<TopologySpec> spec) {
            spec.accept(topology);
            return this;
        }

        /**
         * @param spec how the load is offered
         * @return this builder
         */
        public Builder publishers(Consumer<PublisherSpec> spec) {
            spec.accept(publishers);
            return this;
        }

        /**
         * @param spec how the load is consumed
         * @return this builder
         */
        public Builder consumers(Consumer<ConsumerSpec> spec) {
            spec.accept(consumers);
            return this;
        }

        /**
         * @param warmup how long to run before measuring anything
         * @return this builder
         */
        public Builder warmup(Duration warmup) {
            this.warmup = Objects.requireNonNull(warmup, "warmup");
            return this;
        }

        /**
         * @param duration how long the measured window lasts
         * @return this builder
         */
        public Builder runFor(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("a run needs a positive duration");
            }
            this.duration = duration;
            return this;
        }

        /**
         * @param objective something the result has to satisfy, from
         *     {@link org.acemq.workloads.rules.Objective}
         * @return this builder
         */
        public Builder expect(Rule objective) {
            rules.add(Objects.requireNonNull(objective, "objective"));
            return this;
        }

        /**
         * @param rule an extra check to run against the report
         * @return this builder
         */
        public Builder rule(Rule rule) {
            rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /**
         * Runs without the default rules.
         *
         * <p>Worth thinking twice about. The defaults are mostly validity checks, and removing
         * them does not make a run better — it makes a run that silently failed to offer its
         * load look like a successful one.
         *
         * @return this builder
         */
        public Builder withoutDefaultRules() {
            rules.removeAll(Rules.defaults());
            rules.clear();
            return this;
        }

        /**
         * The management API, for queue depth and topology facts the AMQP connection cannot see.
         *
         * <p>Optional. Without it the report simply omits the queue depth, which is one fewer
         * way to tell a queue that is still draining from messages that were lost.
         *
         * @param url the management URL, for example {@code http://localhost:15672}
         * @param user a broker user with the monitoring tag
         * @param password its password
         * @return this builder
         */
        public Builder management(String url, String user, String password) {
            this.managementUrl = url;
            this.managementUser = user;
            this.managementPassword = password;
            return this;
        }

        /** @return the workload */
        public Workload build() {
            if (publishers.isUnthrottled() && consumers.isEnabled()) {
                // Not refused: an unthrottled run is a legitimate way to find the ceiling. But
                // the latency it produces is subject to coordinated omission, and the report
                // needs to be read with that in mind.
                rules.add(report -> java.util.Optional.of(
                        org.acemq.workloads.rules.Finding.of("unthrottled-latency",
                                org.acemq.workloads.rules.Severity.WARNING,
                                "this run was unthrottled, so there was no schedule to be late against",
                                "the throughput here is meaningful and the latency is not: when the"
                                        + " broker stalls, an unthrottled generator stalls with it and"
                                        + " stops offering load, which makes the recorded latency"
                                        + " better the worse the stall was. Find the ceiling here,"
                                        + " then measure latency with rate() set below it")));
            }
            return new Workload(this);
        }

        /**
         * @param brokerUrl an AMQP URL
         * @return the report, building and running in one step
         */
        public WorkloadReport run(String brokerUrl) {
            return build().run(brokerUrl);
        }
    }
}
