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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the workload publishes to and consumes from.
 *
 * <pre>{@code
 * .topology(t -> t
 *         .exchange("orders", "topic")
 *         .queue("orders.new").quorum()
 *         .boundTo("orders", "order.created"))
 * }</pre>
 *
 * <p>One exchange and one queue, deliberately. A workload that fans out across many queues is a
 * different measurement, and combining the two in one spec produces results nobody can attribute
 * — the interesting comparison is several <em>runs</em>, not one run with everything in it.
 */
public final class TopologySpec {

    private String exchange = "";
    private String exchangeType = "topic";
    private String queue = "acemq.workload";
    private String routingKey = "acemq.workload";
    // Whether anybody asked for that key, as opposed to it being the field initialiser. On the
    // default exchange the two mean different things: an unset key follows the queue, a set one
    // is obeyed and then refused by the validator if it cannot route.
    private boolean routingKeyWasSet;
    private String queueType = "classic";
    private final Map<String, Object> queueArguments = new LinkedHashMap<>();
    private boolean declare = true;

    TopologySpec() {
    }

    /**
     * @param name the exchange to publish to. The default exchange is {@code ""}, which routes
     *     by queue name and skips exchange routing entirely — the fastest path, and not what
     *     most systems do
     * @param type {@code topic}, {@code direct}, {@code fanout} or {@code headers}
     * @return this spec
     */
    public TopologySpec exchange(String name, String type) {
        this.exchange = Objects.requireNonNull(name, "name");
        this.exchangeType = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * @param name the queue to consume from
     * @return this spec
     */
    public TopologySpec queue(String name) {
        this.queue = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * @param exchangeName the exchange to bind to
     * @param key the binding key, which is also the routing key publishes will use
     * @return this spec
     */
    public TopologySpec boundTo(String exchangeName, String key) {
        this.exchange = Objects.requireNonNull(exchangeName, "exchangeName");
        this.routingKey = Objects.requireNonNull(key, "key");
        this.routingKeyWasSet = true;
        return this;
    }

    /**
     * @param key the routing key publishes use, when it differs from the binding key
     * @return this spec
     */
    public TopologySpec routingKey(String key) {
        this.routingKey = Objects.requireNonNull(key, "key");
        this.routingKeyWasSet = true;
        return this;
    }

    /**
     * A quorum queue: replicated, durable, and slower than classic by design.
     *
     * <p>Worth measuring rather than assuming. The throughput difference against a classic queue
     * is large, and it is the price of the guarantee — a comparison run is the honest way to
     * decide whether the guarantee is affordable at your rate.
     *
     * @return this spec
     */
    public TopologySpec quorum() {
        this.queueType = "quorum";
        return this;
    }

    /** @return this spec, with a classic queue (the default) */
    public TopologySpec classic() {
        this.queueType = "classic";
        return this;
    }

    /**
     * @param name a queue argument
     * @param value its value
     * @return this spec
     */
    public TopologySpec argument(String name, Object value) {
        queueArguments.put(Objects.requireNonNull(name, "name"), value);
        return this;
    }

    /**
     * Uses the topology as it already exists rather than declaring it.
     *
     * <p>The right choice when measuring a real environment: declaring would either be refused
     * for mismatched arguments or, worse, would create something subtly different from what
     * production runs and measure that instead.
     *
     * @return this spec
     */
    public TopologySpec useExisting() {
        this.declare = false;
        return this;
    }

    public String exchange() {
        return exchange;
    }

    public String exchangeType() {
        return exchangeType;
    }

    public String queue() {
        return queue;
    }

    /**
     * The key publishes go out with.
     *
     * <p>On the default exchange the routing key <em>is</em> the queue name — that is the whole of
     * how the default exchange routes — so naming a queue and not naming a key has exactly one
     * sensible reading, and it is the queue. Returning the unrelated default instead published
     * every message to a queue of that name, which in general does not exist, and the broker drops
     * an unroutable message on the default exchange without saying anything. Publishers publishing,
     * confirms arriving, queue empty, consumers idle: a run that looks alive and changes nothing.
     * A drill cluster ran that way for a while before anybody noticed.
     *
     * <p>Only when the key was never set. Someone who writes both a queue and a routing key means
     * both, even on the default exchange where that combination cannot route — and the scenario's
     * own validation refuses that by name rather than it being quietly corrected here.
     *
     * @return the routing key, which on the default exchange defaults to the queue name
     */
    public String routingKey() {
        return usesDefaultExchange() && !routingKeyWasSet ? queue : routingKey;
    }

    public String queueType() {
        return queueType;
    }

    public Map<String, Object> queueArguments() {
        return java.util.Collections.unmodifiableMap(queueArguments);
    }

    public boolean shouldDeclare() {
        return declare;
    }

    /** @return whether publishes go through the default exchange */
    public boolean usesDefaultExchange() {
        return exchange.isEmpty();
    }

    @Override
    public String toString() {
        // routingKey() rather than the field: --dry-run prints this, and printing the field showed
        // the key that would not be used. The whole point of reading a dry run is to see what the
        // run will actually do.
        return "TopologySpec{" + (usesDefaultExchange() ? "(default)" : exchange)
                + " -> " + queue + " [" + routingKey() + "] " + queueType + "}";
    }
}
