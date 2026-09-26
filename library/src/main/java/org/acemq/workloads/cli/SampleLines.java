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

import java.io.PrintStream;

import org.acemq.workloads.RunListener;
import org.acemq.workloads.Sample;

/**
 * Writes one line of JSON per reading, as the run takes them.
 *
 * <p>For a run that is being watched by something else while it happens. The report says what a
 * run measured once it is over, and that is the wrong shape for two cases: a standing load that
 * has no end, and anything asking <em>what is true right now</em> — a fault drill that wants to
 * know whether the client noticed the broker blocking, at the moment it was blocked.
 *
 * <p>Standard output rather than a file or a port. A container's log is already collected by
 * whatever is running it, so this needs no volume to mount, no port to publish and no second
 * failure mode; a reader tails the log it already has. Opening a socket to serve numbers that the
 * process is printing anyway would be a server to secure, start, and explain.
 *
 * <p>One object per line, nothing wrapped around them, so a reader can take the last line without
 * parsing the ones before it and a truncated write costs one reading rather than the file.
 *
 * <p>What a line does not carry is a reconnection count, because a {@link Sample} does not have
 * one. A drill can see publishing fail and then resume, which is the shape of a reconnection and
 * not proof of one.
 */
final class SampleLines implements RunListener {

    private final PrintStream out;

    SampleLines(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onSample(Sample sample) {
        StringBuilder line = new StringBuilder(256);
        line.append("{\"at\":\"").append(sample.at()).append('"')
                .append(",\"elapsedMs\":").append(sample.elapsed().toMillis())
                .append(",\"phase\":\"").append(sample.phase()).append('"')
                .append(",\"published\":").append(sample.published())
                .append(",\"confirmed\":").append(sample.confirmed())
                .append(",\"failed\":").append(sample.failed())
                .append(",\"consumed\":").append(sample.consumed())
                .append(",\"publishRate\":").append(round(sample.publishRate()))
                .append(",\"consumeRate\":").append(round(sample.consumeRate()))
                // The field a drill about back pressure is actually asking for. A broker under a
                // memory or disk alarm stops reading from publishing connections, and whether the
                // client noticed is the thing no broker-side probe can answer.
                .append(",\"blocked\":").append(sample.blocked());
        if (sample.queueDepth() != null) {
            line.append(",\"queueDepth\":").append(sample.queueDepth());
        }
        appendLatency(line, "endToEndP99Ms", sample.endToEnd());
        line.append('}');

        // println rather than a buffered writer flushed later: a reader tailing this while a fault
        // is in effect needs the line when it happens, and a run that is killed mid-fault must not
        // lose the readings that describe the fault.
        out.println(line);
    }

    private static void appendLatency(StringBuilder line, String key,
            org.acemq.workloads.metrics.LatencySummary summary) {
        if (summary == null || summary.count() == 0) {
            return;
        }
        line.append(",\"").append(key).append("\":")
                .append(summary.p99().toNanos() / 1_000_000.0);
    }

    /** Two decimal places, so a rate reads as a rate rather than as floating point noise. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
