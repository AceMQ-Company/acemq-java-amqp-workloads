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

/**
 * How the load is offered.
 *
 * <pre>{@code
 * .publishers(p -> p
 *         .threads(4)
 *         .rate(50_000)
 *         .payload(Payload.ofBytes(1024))
 *         .confirms(true))
 * }</pre>
 *
 * <h2>Threads and rate are separate on purpose</h2>
 *
 * <p>"Ten producers" is not a load. Ten threads publishing as fast as they can is a different
 * experiment from ten threads publishing five thousand messages a second each, and only the
 * second one has a number you can hold the broker to.
 *
 * <p>{@link #rate(long)} is the offered rate for the whole workload, divided across
 * {@link #threads(int)}. The threads are a property of the client — how much parallelism the
 * generator uses to keep up — and the rate is the property of the experiment.
 */
public final class PublisherSpec {

    private int threads = 1;
    private long rate = 0;
    private Payload payload = Payload.ofBytes(1024);
    private boolean confirms = true;
    private long maxMessages = Long.MAX_VALUE;
    private int maxInFlight = 1_000;

    PublisherSpec() {
    }

    /**
     * @param threads how many threads share the offered rate. More threads help only when one
     *     cannot keep up with the schedule; the report says when that happened
     * @return this spec
     */
    public PublisherSpec threads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("a workload needs at least one publisher thread");
        }
        this.threads = threads;
        return this;
    }

    /**
     * The offered rate, in messages per second, across all publisher threads.
     *
     * <p>This is a <strong>schedule</strong>, not a limit. Message <em>n</em> is due at
     * {@code start + n/rate}, and if the broker cannot keep up the messages fall behind
     * schedule rather than being quietly not sent. That lag is measured and reported, and it is
     * the difference between "the broker did 180,000 a second" and "the broker was asked for
     * 300,000 and fell four seconds behind".
     *
     * @param messagesPerSecond the offered rate
     * @return this spec
     */
    public PublisherSpec rate(long messagesPerSecond) {
        if (messagesPerSecond < 1) {
            throw new IllegalArgumentException("rate must be at least 1 message per second."
                    + " For an unthrottled run use unthrottled(), and read its warning first.");
        }
        this.rate = messagesPerSecond;
        return this;
    }

    /**
     * Publishes as fast as the client can, with no schedule.
     *
     * <p><strong>This measures throughput and invalidates latency.</strong> With no schedule
     * there is no "due" time, so end-to-end latency is measured from the actual send — and a
     * broker that stalls also stalls the generator, which stops offering load, which makes the
     * recorded latency look better the worse the stall was. That is coordinated omission.
     *
     * <p>Use it to find the ceiling. Then set {@link #rate(long)} below the ceiling and measure
     * latency there, which is the number that means something.
     *
     * @return this spec
     */
    public PublisherSpec unthrottled() {
        this.rate = 0;
        return this;
    }

    /**
     * @param payload the message body
     * @return this spec
     */
    public PublisherSpec payload(Payload payload) {
        this.payload = java.util.Objects.requireNonNull(payload, "payload");
        return this;
    }

    /**
     * @param size shorthand for {@code payload(Payload.ofBytes(size))}
     * @return this spec
     */
    public PublisherSpec messageSize(int size) {
        return payload(Payload.ofBytes(size));
    }

    /**
     * Publisher confirms.
     *
     * <p>On by default, and turning them off changes what the throughput number means. Without
     * confirms a publish is "handed to the socket" rather than "accepted by the broker": the
     * rate goes up, and some of those messages were never durably anywhere. A number produced
     * without confirms is not a number to promise a customer.
     *
     * @param confirms whether to wait for the broker to confirm
     * @return this spec
     */
    public PublisherSpec confirms(boolean confirms) {
        this.confirms = confirms;
        return this;
    }

    /**
     * @param max stop after this many messages, whichever comes first with the run duration
     * @return this spec
     */
    public PublisherSpec maxMessages(long max) {
        this.maxMessages = max;
        return this;
    }

    /**
     * How many publishes a thread may have outstanding before it waits.
     *
     * <p>This number is the difference between a generator that can offer a serious rate and one
     * that cannot. A publish that waits for its own confirm costs a network round trip, so a
     * thread doing that is capped at one message per round trip — about 550 a second against a
     * broker 1.8ms away, whatever the broker is capable of. Reaching 300,000 that way would need
     * five hundred threads.
     *
     * <p>Publishing asynchronously with a window of outstanding confirms decouples the offered
     * rate from the round trip. The window still bounds it: when the broker stops confirming,
     * the window fills, sends are delayed, and that delay is reported as send lag rather than
     * quietly disappearing.
     *
     * @param inFlight how many unconfirmed publishes each thread may hold
     * @return this spec
     */
    public PublisherSpec maxInFlight(int inFlight) {
        if (inFlight < 1) {
            throw new IllegalArgumentException("a publisher needs to be allowed at least one"
                    + " outstanding publish");
        }
        this.maxInFlight = inFlight;
        return this;
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    public int threadCount() {
        return threads;
    }

    /** @return the offered rate, or 0 when unthrottled */
    public long rate() {
        return rate;
    }

    public boolean isUnthrottled() {
        return rate == 0;
    }

    public Payload payload() {
        return payload;
    }

    public boolean confirms() {
        return confirms;
    }

    public long maxMessages() {
        return maxMessages;
    }

    @Override
    public String toString() {
        return "PublisherSpec{threads=" + threads
                + ", rate=" + (isUnthrottled() ? "unthrottled" : rate + "/s")
                + ", " + payload + ", confirms=" + confirms + "}";
    }
}
