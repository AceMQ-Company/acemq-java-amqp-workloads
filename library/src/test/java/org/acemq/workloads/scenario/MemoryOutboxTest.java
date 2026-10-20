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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.acemq.amqp.api.Envelope;
import org.acemq.amqp.api.OutboxRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the in-memory outbox")
class MemoryOutboxTest {

    private final List<String> published = new ArrayList<>();
    private final List<String> abandoned = new ArrayList<>();

    private MemoryOutbox outbox(int capacity) {
        return new MemoryOutbox(capacity, new MemoryOutbox.Listener() {
            @Override
            public void published(MemoryOutbox.Entry entry) {
                published.add(entry.record.id() + (entry.measured ? "+" : "-"));
            }

            @Override
            public void abandoned(MemoryOutbox.Entry entry) {
                abandoned.add(entry.record.id());
            }
        });
    }

    private static OutboxRecord record(String id) {
        return OutboxRecord.of("x", "k", Envelope.of("t").id(id).build(), "body");
    }

    @Test
    @DisplayName("hands out oldest first, never a leased record twice, and tells the run what was sent")
    void claimAndPublish() {
        MemoryOutbox outbox = outbox(10);
        outbox.offer(record("a"), true, 1);
        outbox.offer(record("b"), false, 2);
        outbox.offer(record("c"), true, 3);

        List<OutboxRecord> first = outbox.claimBatch(2, Duration.ofMinutes(1));
        assertThat(first).extracting(OutboxRecord::id).containsExactly("a", "b");
        assertThat(outbox.claimBatch(5, Duration.ofMinutes(1))).extracting(OutboxRecord::id)
                .containsExactly("c");

        outbox.markPublished("a");
        outbox.markPublished("b");
        assertThat(published).containsExactly("a+", "b-");
        assertThat(outbox.pendingCount()).isEqualTo(1);
        assertThat(outbox.peak()).isEqualTo(3);
    }

    @Test
    @DisplayName("releases a failed record for another try, and gives up after five")
    void failures() {
        MemoryOutbox outbox = outbox(10);
        outbox.offer(record("a"), true, 1);

        for (int i = 1; i < MemoryOutbox.MAX_ATTEMPTS; i++) {
            assertThat(outbox.claimBatch(1, Duration.ofMinutes(1))).hasSize(1);
            outbox.markFailed("a", "nope");
        }
        assertThat(abandoned).isEmpty();
        outbox.claimBatch(1, Duration.ofMinutes(1));
        outbox.markFailed("a", "nope");

        assertThat(abandoned).containsExactly("a");
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    @DisplayName("refuses a write when full, rather than growing until the heap is gone")
    void bounded() {
        MemoryOutbox outbox = outbox(1);

        assertThat(outbox.offer(record("a"), true, 1)).isTrue();
        assertThat(outbox.offer(record("b"), true, 2)).isFalse();

        outbox.resetPeak();
        assertThat(outbox.peak()).isEqualTo(1);
    }
}
