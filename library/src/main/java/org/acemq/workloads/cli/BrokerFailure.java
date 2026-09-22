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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a broker that never answered from a broker that answered and said no.
 *
 * <h2>Why the difference is worth the code</h2>
 *
 * <p>Both arrive at the command line as the same exception out of the transport, and both used
 * to be reported as exit 4, "the broker could not be reached". But redeclaring an exchange under
 * a different type is refused by a broker that answered perfectly well, and calling that
 * unreachable sends the reader to check firewall rules, hostnames and container start-up for a
 * mistake that is sitting in their own file two lines above the one they are reading.
 *
 * <h2>How they are told apart</h2>
 *
 * <p>By whether the failure carries a reply code, which is the broker speaking. AMQP's
 * {@code channel.close} and {@code connection.close} both carry one, and anything from 300 up is
 * a refusal — 406 for an inequivalent redeclaration, 403 for credentials the broker would not
 * take, 404 for a queue that was supposed to already exist. A connection that was never made
 * carries a socket error and no reply at all, because there was nobody there to reply.
 *
 * <p>Read the whole chain rather than the top message: the transport wraps the refusal in its
 * own "could not declare exchange orders", which names what we were trying to do and not what
 * the broker thought of it.
 */
final class BrokerFailure {

    /** What the broker answered with. 200 is a clean close, so only 300 and up is a refusal. */
    private static final Pattern REPLY_CODE = Pattern.compile("reply-code=(\\d{3})");

    /** The broker's own sentence, which the client library wraps in protocol bookkeeping. */
    private static final Pattern REPLY_TEXT = Pattern.compile("reply-text=(.*?)(?:, class-id=|\\)$|$)");

    private BrokerFailure() {
    }

    /**
     * @param failure what the run threw
     * @return what the broker said when it refused, or {@code null} if it never said anything
     */
    static String refusal(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            String refusal = refusalIn(cause.getMessage());
            if (refusal != null) {
                return refusal;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return null;
    }

    private static String refusalIn(String message) {
        if (message == null) {
            return null;
        }
        Matcher code = REPLY_CODE.matcher(message);
        if (!code.find() || Integer.parseInt(code.group(1)) < 300) {
            return null;
        }
        Matcher text = REPLY_TEXT.matcher(message);
        // The reply text on its own, when there is one: "PRECONDITION_FAILED - inequivalent arg
        // 'type' for exchange 'orders'" is the sentence worth showing somebody, and
        // "channel error; protocol method: #method<channel.close>(...)" around it is not.
        return text.find() && !text.group(1).isBlank()
                ? text.group(1).trim()
                : message.trim();
    }
}
