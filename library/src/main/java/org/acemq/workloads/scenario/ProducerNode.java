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
import java.util.List;
import java.util.Objects;

/**
 * One source of load, aimed at one exchange.
 *
 * <p>Several of them is the point. "Two services publishing to the same exchange, one of them ten
 * times louder" is a shape a single publisher block cannot describe, and it is the shape that
 * produces the failures people are actually trying to reproduce.
 *
 * <p><strong>Say the rate, not the threads.</strong> {@link #rate(long)} is the number that means
 * something — messages a second, offered on a schedule. How many threads it takes to offer that is
 * arithmetic, and {@link #threads(int)} is there for when the arithmetic is wrong.
 */
public final class ProducerNode implements Node {

    private final String name;
    private String exchange = "";
    private final List<String> routingKeys = new ArrayList<>();
    private long rate = 1_000;
    private int threads = 0;
    private int messageSize = 1024;
    private boolean confirms = true;
    private boolean enabled = true;
    private long maxMessages = Long.MAX_VALUE;
    private int maxInFlight = 1_000;
    private final Expect expect = new Expect();

    ProducerNode(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * @param exchange where it publishes, empty for the default exchange
     * @param routingKey the key it publishes under
     * @return this producer
     */
    public ProducerNode to(String exchange, String routingKey) {
        this.exchange = exchange == null ? "" : exchange;
        if (routingKey != null && !routingKey.isBlank()) {
            routingKeys.add(routingKey);
        }
        return this;
    }

    /**
     * Publishes across several keys, one after another.
     *
     * <p>What makes a topic exchange behave like a topic exchange. A producer on a single key
     * measures one binding however many bindings the exchange has.
     *
     * @param keys the keys, used in turn
     * @return this producer
     */
    public ProducerNode routingKeys(String... keys) {
        routingKeys.clear();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                routingKeys.add(key);
            }
        }
        return this;
    }

    /**
     * @param messagesPerSecond the offered rate, on a schedule
     * @return this producer
     */
    public ProducerNode rate(long messagesPerSecond) {
        if (messagesPerSecond < 0) {
            throw new IllegalArgumentException("a rate cannot be negative");
        }
        this.rate = messagesPerSecond;
        return this;
    }

    /**
     * As fast as it can, with no schedule.
     *
     * <p>Finds the ceiling, and makes the latency meaningless while doing it: an unthrottled
     * generator stalls when the broker stalls, so it stops offering load exactly when the load
     * was the interesting part.
     *
     * @return this producer
     */
    public ProducerNode unthrottled() {
        this.rate = 0;
        return this;
    }

    /**
     * @param threads how many threads offer this producer's rate, or 0 to work it out
     * @return this producer
     */
    public ProducerNode threads(int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("threads cannot be negative");
        }
        this.threads = threads;
        return this;
    }

    /**
     * @param bytes how big each message body is
     * @return this producer
     */
    public ProducerNode messageSize(int bytes) {
        this.messageSize = bytes;
        return this;
    }

    /**
     * @param confirms whether to wait for the broker to confirm
     * @return this producer
     */
    public ProducerNode confirms(boolean confirms) {
        this.confirms = confirms;
        return this;
    }

    /**
     * @param max stop after this many messages, whatever the clock says
     * @return this producer
     */
    public ProducerNode maxMessages(long max) {
        this.maxMessages = max;
        return this;
    }

    /**
     * @param inFlight how many publishes may be outstanding before it waits
     * @return this producer
     */
    public ProducerNode maxInFlight(int inFlight) {
        this.maxInFlight = inFlight;
        return this;
    }

    /**
     * @param enabled whether it publishes at all
     * @return this producer
     */
    public ProducerNode enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * What this producer is asked to prove.
     *
     * @param objectives what to require of it
     * @return this producer
     */
    public ProducerNode expect(java.util.function.Consumer<Expect> objectives) {
        objectives.accept(expect);
        return this;
    }

    /** @return what this producer is asked to prove, empty when nothing was asked */
    public Expect expectations() {
        return expect;
    }

    @Override
    public String name() {
        return name;
    }

    public String exchange() {
        return exchange;
    }

    /** @return the keys it publishes under, never empty: an unset key is the empty one */
    public List<String> routingKeys() {
        return routingKeys.isEmpty() ? List.of("") : List.copyOf(routingKeys);
    }

    /** @return the first key, which is the only one for a producer that named one */
    public String routingKey() {
        return routingKeys().get(0);
    }

    public long rate() {
        return rate;
    }

    public boolean isUnthrottled() {
        return rate == 0;
    }

    /**
     * How many threads this producer actually needs.
     *
     * <p>Worked out from the rate when nobody said: a thread can offer a few tens of thousands a
     * second before the schedule starts slipping, and a run whose generator could not keep up is
     * a run that measured the generator. Somebody who knows better says {@link #threads(int)}.
     *
     * @return the thread count to use
     */
    public int threadCount() {
        if (threads > 0) {
            return threads;
        }
        if (isUnthrottled()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        // Around 20,000 a second per thread, which leaves room on ordinary hardware for the
        // scheduling itself rather than assuming a thread can saturate a link.
        return (int) Math.max(1, Math.min(64, Math.ceil(rate / 20_000.0)));
    }

    public int messageSize() {
        return messageSize;
    }

    public boolean confirms() {
        return confirms;
    }

    public long maxMessages() {
        return maxMessages;
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "Producer{" + name + " -> " + (exchange.isEmpty() ? "(default)" : exchange)
                + " " + routingKeys() + ", " + (isUnthrottled() ? "unthrottled" : rate + "/s")
                + (enabled ? "" : ", off") + "}";
    }
}
