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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Takes the password out of everything the command line prints.
 *
 * <h2>Why this is a stream and not a method</h2>
 *
 * <p>There was already a method. The banner printed before a run called it and the message
 * printed after a failed one did not, so a run against an unreachable broker answered with
 * {@code could not connect to amqp://guest:hunter2@localhost:5672} one line below a banner that
 * had redacted the very same URL. That is the shape this bug always has: a rule everybody
 * assumes is global, applied at the call sites somebody happened to remember.
 *
 * <p>And the call sites are not all ours. A broker quotes the URL back in its own refusal;
 * Jackson quotes the offending line of the file, which is frequently the {@code broker:} line;
 * a library deeper down puts the URL in an exception message we only ever pass through. Every
 * one of those lands in a CI log, and CI logs are archived and often public.
 *
 * <p>So the rule is applied to the stream rather than to the call sites. {@link #wrap} returns a
 * {@link PrintStream} that redacts each line on its way out, and nothing printed through it can
 * miss the rule by arriving along a route nobody thought of.
 */
final class Redaction {

    /**
     * {@code scheme://user:secret@host} — the one shape a URL carries a password in, and the
     * same shape for {@code amqp}, {@code amqps} and the management {@code http}.
     */
    private static final Pattern CREDENTIALS = Pattern.compile("://([^:/@\\s]+):([^@\\s]+)@");

    private Redaction() {
    }

    /**
     * @param text anything about to be printed
     * @return the same text with any URL password replaced by {@code ***}
     */
    static String redact(String text) {
        return text == null ? null : CREDENTIALS.matcher(text).replaceAll("://$1:***@");
    }

    /**
     * @param stream where the output really goes
     * @return a stream that redacts every line written to it
     */
    static PrintStream wrap(PrintStream stream) {
        // Deliberately not auto-flushing. A PrintStream that auto-flushes flushes after every
        // single character written, and a flush is what forces a half-built line out, so
        // auto-flush would hand the line through one character at a time and no redaction
        // could ever match. The line below does the flushing instead, once a line is whole.
        return new PrintStream(new ByLine(stream), false, StandardCharsets.UTF_8);
    }

    /**
     * Holds bytes back until the newline that ends the line they belong to.
     *
     * <p>A stream cannot rewrite what it has already handed on, and a long enough URL can be
     * split across two writes, so redacting each write as it arrives would let a password
     * through at the seam. A line is the smallest unit that is certainly whole.
     *
     * <p>A line that never gets its newline is still emitted on {@link #flush()}, because
     * something waiting for an answer is worth printing.
     */
    private static final class ByLine extends OutputStream {

        private final PrintStream target;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        ByLine(PrintStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            pending.write(b);
            if (b == '\n') {
                emit();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            for (int i = 0; i < length; i++) {
                write(bytes[offset + i]);
            }
        }

        @Override
        public void flush() {
            emit();
            target.flush();
        }

        @Override
        public void close() {
            flush();
        }

        private void emit() {
            if (pending.size() == 0) {
                return;
            }
            target.print(redact(pending.toString(StandardCharsets.UTF_8)));
            target.flush();
            pending.reset();
        }
    }
}
