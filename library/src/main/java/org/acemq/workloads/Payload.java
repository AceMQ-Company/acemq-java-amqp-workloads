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

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The bytes a workload publishes, and the header this library needs inside them.
 *
 * <p>Every message carries a 16-byte prefix: the <strong>intended</strong> send time in
 * nanoseconds, and a sequence number. Both are read back by the consumer.
 *
 * <h2>Why the intended time and not the actual one</h2>
 *
 * <p>This is the difference between a latency number that is true and one that flatters the
 * broker.
 *
 * <p>Suppose the schedule says publish at t=0, 1ms, 2ms, 3ms, and the broker stalls for 100ms
 * after the first. A generator that timestamps at the moment of the actual send records four
 * messages that each took a millisecond or two: the stall is invisible, because the generator
 * was stalled with it and never offered the load it was supposed to. That is
 * <em>coordinated omission</em>, and it makes latency look better the worse the broker behaves.
 *
 * <p>Timestamping the moment the message was <em>due</em> records 100ms, 99ms, 98ms, 97ms —
 * which is what a client waiting on that schedule actually experienced.
 */
public final class Payload {

    /** Two longs: the intended send time and the sequence number. */
    public static final int HEADER_BYTES = 16;

    private final int size;
    private final boolean random;

    private Payload(int size, boolean random) {
        if (size < HEADER_BYTES) {
            throw new IllegalArgumentException("a payload needs at least " + HEADER_BYTES
                    + " bytes for the timestamp and sequence this library reads back;"
                    + " " + size + " is too small");
        }
        this.size = size;
        this.random = random;
    }

    /**
     * @param size total message size in bytes, including the {@value #HEADER_BYTES}-byte header
     * @return a payload of zero-filled bytes after the header
     */
    public static Payload ofBytes(int size) {
        return new Payload(size, false);
    }

    /**
     * Random bytes after the header.
     *
     * <p>Worth using when anything downstream compresses or deduplicates. A run of zeroes
     * compresses to nothing, and a broker or network that compresses will report a throughput
     * that no real payload will reproduce.
     *
     * @param size total message size in bytes
     * @return a payload of random bytes after the header
     */
    public static Payload ofRandomBytes(int size) {
        return new Payload(size, true);
    }

    /** @return the message size in bytes */
    public int size() {
        return size;
    }

    /** @return whether the body after the header is random rather than zeroes */
    public boolean isRandom() {
        return random;
    }

    /**
     * Builds one message body.
     *
     * @param intendedSendNanos when this message was due, from {@link System#nanoTime()}
     * @param sequence the message's sequence number within its publisher
     * @return the body
     */
    public byte[] build(long intendedSendNanos, long sequence) {
        byte[] body = new byte[size];
        if (random) {
            ThreadLocalRandom.current().nextBytes(body);
        }
        ByteBuffer.wrap(body).putLong(intendedSendNanos).putLong(sequence);
        return body;
    }

    /**
     * @param body a message built by {@link #build(long, long)}
     * @return the time the message was due, in {@link System#nanoTime()} terms
     */
    public static long intendedSendNanos(byte[] body) {
        return ByteBuffer.wrap(body).getLong(0);
    }

    /**
     * @param body a message built by {@link #build(long, long)}
     * @return its sequence number
     */
    public static long sequence(byte[] body) {
        return ByteBuffer.wrap(body).getLong(8);
    }

    /**
     * @param body a received body
     * @return whether it is long enough to have been produced by this library. A queue with
     *     messages from somewhere else in it would otherwise produce nonsense latencies rather
     *     than an obvious failure
     */
    public static boolean isWorkloadMessage(byte[] body) {
        return body != null && body.length >= HEADER_BYTES;
    }

    @Override
    public String toString() {
        return "Payload{" + size + " bytes" + (random ? ", random" : "") + "}";
    }
}
