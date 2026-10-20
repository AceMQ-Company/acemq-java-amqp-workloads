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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import org.acemq.amqp.api.AceMqException;
import org.acemq.amqp.api.OutboxRecord;
import org.acemq.amqp.api.OutboxStore;

/**
 * An outbox in memory, for loading the library's relay rather than a database.
 *
 * <p>It keeps, beside each record, what the run needs to account for it: whether it was written
 * inside the measured window and when, so the relay's confirm can be counted against the window
 * the write belonged to — the same latch a direct publish keeps.
 *
 * <p>Bounded. An outbox in a database grows until the disk is full, which is the honest failure
 * of a relay that cannot keep up; one in this process would take the generator down with it
 * first. A write to a full outbox is refused and counted, so the report says the relay fell
 * behind instead of the generator running out of heap.
 */
final class MemoryOutbox implements OutboxStore {

    /** Attempts before a record is abandoned, as {@code JdbcOutboxStore} defaults to. */
    static final int MAX_ATTEMPTS = 5;

    /** What the run is told as records leave the outbox. */
    interface Listener {
        /** The relay's publish of this record was confirmed. */
        void published(Entry entry);

        /** The record failed {@link #MAX_ATTEMPTS} times and was given up on. */
        void abandoned(Entry entry);
    }

    /** One record and its accounting. */
    static final class Entry {
        final OutboxRecord record;
        final boolean measured;
        final long writtenNanos;
        long leasedUntil;
        int attempts;

        Entry(OutboxRecord record, boolean measured, long writtenNanos) {
            this.record = record;
            this.measured = measured;
            this.writtenNanos = writtenNanos;
        }
    }

    // ponytail: one lock over an insertion-ordered map. The relay is a single thread publishing
    // with a confirm per record, so it is the bottleneck long before this is.
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final int capacity;
    private final Listener listener;
    private long peak;

    MemoryOutbox(int capacity, Listener listener) {
        this.capacity = capacity;
        this.listener = listener;
    }

    /**
     * @param record the message
     * @param measured whether it was written inside the measured window
     * @param writtenNanos when, from {@link System#nanoTime()}
     * @return false when the outbox is full and the record was not written
     */
    synchronized boolean offer(OutboxRecord record, boolean measured, long writtenNanos) {
        if (entries.size() >= capacity) {
            return false;
        }
        entries.put(record.id(), new Entry(record, measured, writtenNanos));
        peak = Math.max(peak, entries.size());
        return true;
    }

    @Override
    public void add(OutboxRecord record) {
        if (!offer(record, false, System.nanoTime())) {
            throw new AceMqException("the outbox is full: " + capacity + " records are waiting");
        }
    }

    @Override
    public synchronized List<OutboxRecord> claimBatch(int batchSize, Duration lease) {
        long now = System.nanoTime();
        List<OutboxRecord> batch = new ArrayList<>(Math.min(batchSize, entries.size()));
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext() && batch.size() < batchSize; ) {
            Entry entry = it.next();
            if (entry.leasedUntil - now <= 0) {
                entry.leasedUntil = now + lease.toNanos();
                batch.add(entry.record);
            }
        }
        return batch;
    }

    @Override
    public void markPublished(String id) {
        Entry entry;
        synchronized (this) {
            entry = entries.remove(id);
        }
        if (entry != null) {
            listener.published(entry);
        }
    }

    @Override
    public void markFailed(String id, String reason) {
        Entry abandoned = null;
        synchronized (this) {
            Entry entry = entries.get(id);
            if (entry == null) {
                return;
            }
            entry.attempts++;
            entry.leasedUntil = 0;
            if (entry.attempts >= MAX_ATTEMPTS) {
                abandoned = entries.remove(id);
            }
        }
        if (abandoned != null) {
            listener.abandoned(abandoned);
        }
    }

    @Override
    public synchronized long pendingCount() {
        return entries.size();
    }

    /** @return the most records waiting at once since the last {@link #resetPeak()} */
    synchronized long peak() {
        return peak;
    }

    /** Starts the peak again from what is waiting now, for the measured window. */
    synchronized void resetPeak() {
        peak = entries.size();
    }
}
