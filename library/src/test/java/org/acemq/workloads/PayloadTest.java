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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the message payload")
class PayloadTest {

    @Test
    @DisplayName("carries the intended send time and sequence back out")
    void roundTrip() {
        Payload payload = Payload.ofBytes(64);

        byte[] body = payload.build(1_234_567_890L, 42);

        assertThat(body).hasSize(64);
        assertThat(Payload.intendedSendNanos(body)).isEqualTo(1_234_567_890L);
        assertThat(Payload.sequence(body)).isEqualTo(42);
    }

    @Test
    @DisplayName("a negative nanoTime survives the round trip")
    void negativeNanoTime() {
        // System.nanoTime() has an arbitrary origin and is routinely negative on a fresh JVM.
        // A payload that only handled positive values would produce garbage latencies for the
        // first few minutes of uptime on some platforms.
        byte[] body = Payload.ofBytes(32).build(-9_000_000_000L, 1);

        assertThat(Payload.intendedSendNanos(body)).isEqualTo(-9_000_000_000L);
    }

    @Test
    @DisplayName("the header is written over random bytes, not next to them")
    void randomBodyKeepsHeader() {
        byte[] body = Payload.ofRandomBytes(128).build(777L, 3);

        assertThat(Payload.intendedSendNanos(body)).isEqualTo(777L);
        assertThat(Payload.sequence(body)).isEqualTo(3);
        assertThat(body).hasSize(128);
    }

    @Test
    @DisplayName("a payload too small for the header is refused at build time")
    void tooSmall() {
        // Otherwise the failure is a nonsensical latency at run time, hours later.
        assertThatThrownBy(() -> Payload.ofBytes(8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    @Test
    @DisplayName("a foreign message is recognised rather than decoded as nonsense")
    void foreignMessage() {
        assertThat(Payload.isWorkloadMessage(new byte[] {1, 2, 3})).isFalse();
        assertThat(Payload.isWorkloadMessage(null)).isFalse();
        assertThat(Payload.isWorkloadMessage(new byte[16])).isTrue();
    }
}
