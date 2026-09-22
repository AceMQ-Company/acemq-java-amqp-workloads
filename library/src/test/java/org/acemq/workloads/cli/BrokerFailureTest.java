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
package org.acemq.workloads.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two failures that used to be one exit code.
 *
 * <p>The messages here are verbatim from a broker in {@code CliIT}, wrapped the way the
 * transport wraps them, because the whole difficulty is that both arrive as the same exception
 * type and only the text tells them apart.
 */
@DisplayName("telling a silent broker from one that said no")
class BrokerFailureTest {

    @Test
    @DisplayName("a refused redeclaration is the broker answering")
    void inequivalentExchange() {
        RuntimeException failure = new RuntimeException("could not declare exchange orders",
                new IOException(new RuntimeException(
                        "channel error; protocol method: #method<channel.close>(reply-code=406,"
                                + " reply-text=PRECONDITION_FAILED - inequivalent arg 'type' for"
                                + " exchange 'orders' in vhost '/': received 'direct' but current"
                                + " is 'topic', class-id=40, method-id=10)")));

        assertThat(BrokerFailure.refusal(failure))
                .isEqualTo("PRECONDITION_FAILED - inequivalent arg 'type' for exchange 'orders'"
                        + " in vhost '/': received 'direct' but current is 'topic'");
    }

    @Test
    @DisplayName("so are credentials the broker would not take")
    void accessRefused() {
        RuntimeException failure = new RuntimeException("could not connect",
                new RuntimeException("connection error; protocol method:"
                        + " #method<connection.close>(reply-code=403, reply-text=ACCESS_REFUSED -"
                        + " Login was refused, class-id=10, method-id=50)"));

        assertThat(BrokerFailure.refusal(failure)).contains("ACCESS_REFUSED");
    }

    @Test
    @DisplayName("a connection that was never made is not")
    void neverConnected() {
        RuntimeException failure = new RuntimeException("could not connect to amqp://localhost:1",
                new ConnectException("Connection refused"));

        assertThat(BrokerFailure.refusal(failure)).isNull();
    }

    @Test
    @DisplayName("nor is a clean close, which carries reply-code 200")
    void cleanClose() {
        // 200 is the broker being polite on the way out, not refusing anything. Treating any
        // reply code as a refusal would turn an ordinary disconnection into "your file is wrong".
        RuntimeException failure = new RuntimeException("the run ended early",
                new RuntimeException("clean connection shutdown; protocol method:"
                        + " #method<connection.close>(reply-code=200, reply-text=OK,"
                        + " class-id=0, method-id=0)"));

        assertThat(BrokerFailure.refusal(failure)).isNull();
    }

    @Test
    @DisplayName("a failure with nothing to say is not a refusal")
    void noMessage() {
        assertThat(BrokerFailure.refusal(new RuntimeException())).isNull();
    }

    @Test
    @DisplayName("a cause that is its own cause does not hang the search")
    void selfReferentialCause() {
        // Not hypothetical: an exception initialised with itself is a real thing libraries do,
        // and walking a chain is exactly where it becomes an infinite loop.
        RuntimeException failure = new RuntimeException("round and round") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(BrokerFailure.refusal(failure)).isNull();
    }
}
