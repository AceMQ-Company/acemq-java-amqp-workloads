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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A whole topology under load, rather than one queue.
 *
 * <p>{@link org.acemq.workloads.Workload} measures a single path: one exchange, one queue, one
 * rate. That is the right shape for "how fast is a quorum queue", and the wrong shape for the
 * question people actually arrive with — <em>what happens to this topology, the one we run, when
 * Monday morning hits it</em>. A fan-out where one consumer is slow, a dead-letter path that only
 * matters under load, two services sharing an exchange: none of those are a single path, and
 * measuring them one queue at a time measures something else.
 *
 * <p>A scenario is that topology plus who is pushing and who is pulling:
 *
 * <pre>{@code
 * Scenario scenario = Scenario.named("monday-morning")
 *         .exchange("orders", "topic")
 *         .queue("orders.shipping", q -> q.quorum().boundTo("orders", "order.*"))
 *         .queue("orders.audit", q -> q.classic().boundTo("orders", "#"))
 *         .producer("checkout", p -> p.to("orders", "order.placed").rate(20_000).messageSize(512))
 *         .consumers("orders.shipping", c -> c.concurrency(8).prefetch(200).handlerTime(1ms))
 *         .consumers("orders.audit", c -> c.concurrency(2).prefetch(50))
 *         .warmup(Duration.ofSeconds(10))
 *         .runFor(Duration.ofMinutes(2));
 * }</pre>
 *
 * <p><strong>Every node can be switched off without being deleted.</strong> A designer needs that
 * more than it needs anything else: the interesting question is usually "what happens if this
 * consumer stops", and answering it by deleting the consumer loses the thing you were about to
 * put back.
 */
public final class Scenario {

    private final String name;
    private String description = "";
    private final List<ExchangeNode> exchanges = new ArrayList<>();
    private final List<QueueNode> queues = new ArrayList<>();
    private final List<ProducerNode> producers = new ArrayList<>();
    private Duration warmup = Duration.ofSeconds(5);
    private Duration duration = Duration.ofSeconds(30);
    private boolean declare = true;

    private Scenario(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * @param name what this scenario is called, and what its report is filed under
     * @return a new scenario
     */
    public static Scenario named(String name) {
        return new Scenario(name);
    }

    /**
     * @param text what this scenario is for, in a sentence
     * @return this scenario
     */
    public Scenario describedAs(String text) {
        this.description = text == null ? "" : text;
        return this;
    }

    /**
     * @param name the exchange
     * @param type direct, topic, fanout or headers
     * @return this scenario
     */
    public Scenario exchange(String name, String type) {
        exchanges.add(new ExchangeNode(name, type));
        return this;
    }

    /**
     * @param name the queue
     * @param spec its type, arguments and bindings
     * @return this scenario
     */
    public Scenario queue(String name, java.util.function.Consumer<QueueNode> spec) {
        QueueNode node = new QueueNode(name);
        spec.accept(node);
        queues.add(node);
        return this;
    }

    /**
     * @param name the queue, with defaults
     * @return this scenario
     */
    public Scenario queue(String name) {
        return queue(name, q -> { });
    }

    /**
     * @param name what to call this producer, so its numbers can be told apart
     * @param spec where it publishes and how hard
     * @return this scenario
     */
    public Scenario producer(String name, java.util.function.Consumer<ProducerNode> spec) {
        ProducerNode node = new ProducerNode(name);
        spec.accept(node);
        producers.add(node);
        return this;
    }

    /**
     * Attaches consumers to a queue that is already in the scenario.
     *
     * @param queueName the queue
     * @param spec how many, what prefetch, how slow the handler is
     * @return this scenario
     * @throws IllegalArgumentException if no queue of that name has been added
     */
    public Scenario consumers(String queueName, java.util.function.Consumer<ConsumerGroupNode> spec) {
        QueueNode queue = findQueue(queueName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no queue named " + queueName + " in this scenario; add it before"
                                + " attaching consumers to it"));
        spec.accept(queue.consumersNode());
        return this;
    }

    /**
     * @param warmup how long to run before the counters are reset
     * @return this scenario
     */
    public Scenario warmup(Duration warmup) {
        this.warmup = Objects.requireNonNull(warmup, "warmup");
        return this;
    }

    /**
     * @param duration how long to measure for
     * @return this scenario
     */
    public Scenario runFor(Duration duration) {
        this.duration = Objects.requireNonNull(duration, "duration");
        return this;
    }

    /**
     * Runs against the topology as it already exists.
     *
     * <p>What to use against a real environment: declaring would either be refused for mismatched
     * arguments or, worse, quietly create something subtly different from what production runs
     * and measure that instead.
     *
     * @return this scenario
     */
    public Scenario useExisting() {
        this.declare = false;
        return this;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<ExchangeNode> exchanges() {
        return List.copyOf(exchanges);
    }

    public List<QueueNode> queues() {
        return List.copyOf(queues);
    }

    public List<ProducerNode> producers() {
        return List.copyOf(producers);
    }

    /** @return the exchanges that are switched on */
    public List<ExchangeNode> activeExchanges() {
        return exchanges.stream().filter(Node::isEnabled).toList();
    }

    /** @return the queues that are switched on */
    public List<QueueNode> activeQueues() {
        return queues.stream().filter(Node::isEnabled).toList();
    }

    /** @return the producers that are switched on */
    public List<ProducerNode> activeProducers() {
        return producers.stream().filter(Node::isEnabled).toList();
    }

    /**
     * @param name a queue name
     * @return that queue, if it is in this scenario
     */
    public Optional<QueueNode> findQueue(String name) {
        return queues.stream().filter(q -> q.name().equals(name)).findFirst();
    }

    public Duration warmup() {
        return warmup;
    }

    public Duration duration() {
        return duration;
    }

    public boolean shouldDeclare() {
        return declare;
    }

    /**
     * What is wrong with this scenario, before a broker sees any of it.
     *
     * <p>Every one of these is a mistake a designer makes constantly — a binding to a queue that
     * was renamed, a producer aimed at an exchange somebody switched off — and every one of them
     * fails at the broker in a way that reads like a broker problem.
     *
     * @return the problems, empty when there are none
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();

        Set<String> exchangeNames = new LinkedHashSet<>();
        for (ExchangeNode exchange : exchanges) {
            if (!exchangeNames.add(exchange.name())) {
                problems.add("exchange " + exchange.name() + " is declared twice");
            }
            if (exchange.type() == null || exchange.type().isBlank()) {
                problems.add("exchange " + exchange.name()
                        + " has no type (direct, topic, fanout or headers)");
            }
        }

        Set<String> queueNames = new LinkedHashSet<>();
        for (QueueNode queue : queues) {
            if (!queueNames.add(queue.name())) {
                problems.add("queue " + queue.name() + " is declared twice");
            }
            for (Binding binding : queue.bindings()) {
                if (binding.exchange().isBlank()) {
                    problems.add("queue " + queue.name()
                            + " is bound to the default exchange, which cannot be bound to");
                } else if (!exchangeNames.contains(binding.exchange())) {
                    problems.add("queue " + queue.name() + " is bound to exchange "
                            + binding.exchange() + ", which this scenario does not declare");
                }
            }
            if (queue.deadLetterExchange() != null
                    && !exchangeNames.contains(queue.deadLetterExchange())) {
                problems.add("queue " + queue.name() + " dead-letters to "
                        + queue.deadLetterExchange() + ", which this scenario does not declare");
            }
        }

        Set<String> producerNames = new LinkedHashSet<>();
        for (ProducerNode producer : producers) {
            if (!producerNames.add(producer.name())) {
                problems.add("producer " + producer.name() + " is named twice");
            }
            String target = producer.exchange();
            if (!target.isEmpty() && !exchangeNames.contains(target)) {
                problems.add("producer " + producer.name() + " publishes to exchange " + target
                        + ", which this scenario does not declare");
            }
            if (target.isEmpty() && !queueNames.contains(producer.routingKey())) {
                // The default exchange routes by queue name, so the key has to be one.
                problems.add("producer " + producer.name() + " publishes to the default exchange"
                        + " with key " + producer.routingKey()
                        + ", which is not a queue in this scenario");
            }
        }

        if (activeProducers().isEmpty()) {
            problems.add("nothing is publishing: every producer is switched off");
        }
        if (activeQueues().isEmpty()) {
            problems.add("nothing is receiving: every queue is switched off");
        }

        return problems;
    }

    /**
     * Warnings about a scenario that will run but may not measure what was intended.
     *
     * <p>Kept apart from {@link #problems()} on purpose. A queue nobody consumes is a legitimate
     * thing to measure — it is how you find out what a backlog costs — so it is worth saying and
     * not worth refusing.
     *
     * @return the warnings, empty when there are none
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        for (QueueNode queue : activeQueues()) {
            if (!queue.consumersNode().isEnabled()) {
                warnings.add("nothing consumes " + queue.name()
                        + ", so it will grow for the length of the run");
            }
            if (queue.bindings().isEmpty()) {
                warnings.add("nothing is bound to " + queue.name()
                        + ", so it will only receive what is published to it by name");
            }
        }
        for (ExchangeNode exchange : activeExchanges()) {
            boolean bound = queues.stream().flatMap(q -> q.bindings().stream())
                    .anyMatch(b -> b.exchange().equals(exchange.name()));
            if (!bound) {
                warnings.add("nothing is bound to exchange " + exchange.name()
                        + ", so anything published to it is discarded");
            }
        }
        return warnings;
    }

    @Override
    public String toString() {
        return "Scenario{" + name + ", " + exchanges.size() + " exchanges, " + queues.size()
                + " queues, " + producers.size() + " producers, for " + duration + "}";
    }
}
