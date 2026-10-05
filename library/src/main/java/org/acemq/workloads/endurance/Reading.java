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
package org.acemq.workloads.endurance;

/**
 * One client, read once from outside: one line of the readings TSV.
 *
 * <p>A client that is gone is recorded rather than skipped, with zeros and {@code -1} counters,
 * because a dead load has to reach the verdict: the one thing it cannot do is grow.
 *
 * @param epoch seconds since the epoch
 * @param phase {@code baseline}, {@code cycling} or {@code final}
 * @param lang the client's name
 * @param rssKb resident memory in kB
 * @param fds open file descriptors
 * @param threads threads
 * @param published the load's own cumulative counter, -1 when unreadable
 * @param consumed likewise
 * @param connections connections on the whole cluster at the time, -1 when unreadable
 */
public record Reading(long epoch, String phase, String lang, long rssKb, long fds, long threads,
        long published, long consumed, long connections) {

    /** The TSV header, unchanged from the script that first wrote it. */
    public static final String HEADER =
            "epoch\tphase\tlang\trss_kb\tfds\tthreads\tpublished\tconsumed\tconnections";

    /** @return this reading as a TSV line, without the newline */
    public String tsv() {
        return epoch + "\t" + phase + "\t" + lang + "\t" + rssKb + "\t" + fds + "\t" + threads
                + "\t" + published + "\t" + consumed + "\t" + connections;
    }

    /** @return the reading a TSV line holds, or null for a header or a malformed line */
    public static Reading parse(String line) {
        String[] c = line.split("\t");
        if (c.length < 9 || c[2].isEmpty()) {
            return null;
        }
        try {
            return new Reading(Long.parseLong(c[0]), c[1], c[2], Long.parseLong(c[3]),
                    Long.parseLong(c[4]), Long.parseLong(c[5]), Long.parseLong(c[6]),
                    Long.parseLong(c[7]), Long.parseLong(c[8]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
