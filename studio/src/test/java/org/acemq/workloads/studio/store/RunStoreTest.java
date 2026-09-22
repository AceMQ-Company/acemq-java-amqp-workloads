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
package org.acemq.workloads.studio.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("what the run history records about a failure")
class RunStoreTest {

    /** Distinctive enough that finding it anywhere means it came from here. */
    private static final String SECRET = "n0t-f0r-the-history";

    @Test
    @DisplayName("never the password, whichever sentence carries it")
    void redactsTheBrokerUrl() {
        // The broker column was redacted and the error column was not, which is the same fault
        // twice over: the rule was applied where somebody thought of it. This text is stored,
        // sent to the browser and copied to whoever is asked for a second opinion, so the
        // assertion is the absence of the secret rather than a particular wording.
        String stored = RunStore.because(new RuntimeException(
                "could not connect to amqps://guest:" + SECRET + "@broker.internal:5671"));

        assertThat(stored).doesNotContain(SECRET).contains("amqps://guest:***@broker.internal");
    }

    @Test
    @DisplayName("not from the root cause either, which is the half worth reading")
    void redactsTheRootCauseToo() {
        String stored = RunStore.because(new RuntimeException("the run failed",
                new IOException("handshake refused by amqps://guest:" + SECRET + "@broker:5671")));

        assertThat(stored).doesNotContain(SECRET).contains("handshake refused");
    }

    @Test
    @DisplayName("and says what actually went wrong when the top of the chain does not")
    void keepsTheRootCause() {
        String stored = RunStore.because(new RuntimeException("could not connect",
                new IOException("certificate is marked development-only")));

        assertThat(stored)
                .contains("could not connect")
                .contains("certificate is marked development-only");
    }
}
