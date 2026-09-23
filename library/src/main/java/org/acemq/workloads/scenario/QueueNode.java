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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A queue in a scenario, its bindings, and the consumers on it.
 *
 * <p>The consumers belong to the queue rather than to the scenario because that is where the
 * interesting asymmetry lives: a fan-out with eight consumers on one queue and one on another is
 * the shape that produces a backlog on exactly one leg, and a model with a single consumer block
 * cannot describe it.
 */
public final class QueueNode implements Node {

    private final String name;
    private QueueType type = QueueType.CLASSIC;
    private boolean durable = true;
    private boolean enabled = true;
    private String deadLetterExchange;
    private final List<Binding> bindings = new ArrayList<>();
    private final Map<String, Object> arguments = new LinkedHashMap<>();
    private final ConsumerGroupNode consumers = new ConsumerGroupNode(this);
    private String broker;
    private final Expect expect = new Expect();

    QueueNode(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** @return this queue, as a classic queue */
    public QueueNode classic() {
        return type(QueueType.CLASSIC);
    }

    /**
     * @return this queue, as a quorum queue: replicated, durable, and slower by design
     */
    public QueueNode quorum() {
        return type(QueueType.QUORUM);
    }

    /**
     * @return this queue, as a stream: an append-only log that consumers read at their own offset
     */
    public QueueNode stream() {
        return type(QueueType.STREAM);
    }

    /**
     * A classic queue mirrored by an HA policy.
     *
     * <p>Only meaningful on RabbitMQ 3.13 and earlier. Mirrored classic queues were removed in
     * 4.0 and quorum queues replaced them, so a scenario using this against a current broker gets
     * a classic queue and a policy nothing honours — which is why the studio hides the option
     * unless the broker it is pointed at is old enough to have it.
     *
     * @return this queue
     */
    public QueueNode mirrored() {
        return type(QueueType.CLASSIC_MIRRORED);
    }

    /**
     * @param type what kind of queue
     * @return this queue
     */
    public QueueNode type(QueueType type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * @param exchange where the messages come from
     * @param routingKey the pattern they arrive under
     * @return this queue
     */
    public QueueNode boundTo(String exchange, String routingKey) {
        bindings.add(new Binding(exchange, routingKey == null ? "" : routingKey));
        return this;
    }

    /**
     * @param exchange where rejected and expired messages go
     * @return this queue
     */
    public QueueNode deadLetterTo(String exchange) {
        this.deadLetterExchange = exchange;
        return this;
    }

    /** @return this queue, not surviving a broker restart */
    public QueueNode transient_() {
        this.durable = false;
        return this;
    }

    /**
     * @param name a queue argument, such as {@code x-max-length}
     * @param value its value
     * @return this queue
     */
    public QueueNode argument(String name, Object value) {
        arguments.put(name, value);
        return this;
    }

    /**
     * The broker this queue actually lives on, when that is not the one the scenario runs
     * against.
     *
     * <p>This is how a federation or shovel link gets measured. The link itself is broker
     * configuration and this library does not declare it -- what it can do is stand on both
     * ends of one: a producer publishes to the upstream broker, the link carries the messages,
     * and a consumer group attached here reads them off the downstream queue. End-to-end
     * latency for such a queue is therefore publish-to-arrival <em>across the link</em>, which
     * is the number people actually want from federation and cannot get from either broker
     * alone.
     *
     * <p>The measurement is sound because both ends run in this one process: the timestamp
     * travels in the payload as a {@link System#nanoTime()} reading, and nanoTime is only
     * comparable within a JVM. Two copies of this tool on two machines would produce a
     * difference between two unrelated clocks rather than a latency.
     *
     * <p>A queue on another broker is never declared and its bindings are never created: the
     * link owns that end, and declaring into it would either duplicate what the link already
     * made or fail against an exchange that exists only upstream.
     *
     * @param url the broker this queue is on, as an AMQP URL
     * @return this queue
     */
    public QueueNode broker(String url) {
        this.broker = url == null || url.isBlank() ? null : url;
        return this;
    }

    /**
     * @param enabled whether it takes part in the run
     * @return this queue
     */
    public QueueNode enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * @param spec how many consumers, what prefetch, how slow the handler
     * @return this queue
     */
    public QueueNode consumers(java.util.function.Consumer<ConsumerGroupNode> spec) {
        spec.accept(consumers);
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    public QueueType type() {
        return type;
    }

    public boolean isDurable() {
        return durable;
    }

    public String deadLetterExchange() {
        return deadLetterExchange;
    }

    public List<Binding> bindings() {
        return List.copyOf(bindings);
    }

    /** @return the broker this queue is on, or null for the one the scenario runs against */
    public String broker() {
        return broker;
    }

    /** @return whether this queue is on the far side of a link rather than on the run's broker */
    public boolean isRemote() {
        return broker != null;
    }

    /** @return the consumers attached to this queue, whether or not they are switched on */
    public ConsumerGroupNode consumersNode() {
        return consumers;
    }

    /**
     * What this queue is asked to prove.
     *
     * <pre>{@code
     * .queue("orders.shipping", q -> q.quorum()
     *         .boundTo("orders", "order.*")
     *         .expect(e -> e.p99Below(Duration.ofMillis(50)).noBacklog(true)))
     * }</pre>
     *
     * @param objectives what to require of it
     * @return this queue
     */
    public QueueNode expect(java.util.function.Consumer<Expect> objectives) {
        objectives.accept(expect);
        return this;
    }

    /** @return what this queue is asked to prove, empty when nothing was asked */
    public Expect expectations() {
        return expect;
    }

    /**
     * The arguments this queue is declared with, the type and dead-letter settings included.
     *
     * @return the arguments a declaration would carry
     */
    public Map<String, Object> declaredArguments() {
        Map<String, Object> declared = new LinkedHashMap<>(arguments);
        switch (type) {
            case QUORUM -> declared.put("x-queue-type", "quorum");
            case STREAM -> declared.put("x-queue-type", "stream");
            // Classic is the default and mirrored is a classic queue with a policy over it, so
            // neither writes an x-queue-type. Saying "classic" explicitly is legal and makes a
            // redeclaration of an existing queue fail for no reason worth failing over.
            case CLASSIC, CLASSIC_MIRRORED -> { }
        }
        if (deadLetterExchange != null && !deadLetterExchange.isBlank()) {
            declared.put("x-dead-letter-exchange", deadLetterExchange);
        }
        return declared;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "Queue{" + name + " (" + type.wireName() + "), " + bindings.size() + " bindings, "
                + consumers + (enabled ? "" : ", off") + "}";
    }
}
