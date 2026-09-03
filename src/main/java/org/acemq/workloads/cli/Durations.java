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

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations as people write them: {@code 500ms}, {@code 30s}, {@code 5m}, {@code 1h}.
 *
 * <p>{@link Duration#parse} wants ISO-8601 — {@code PT30S} — which nobody writing a workload
 * file will type correctly the first time, and which reviews badly in a pull request.
 *
 * <p>A bare number is refused rather than assumed. "{@code runFor: 60}" is a minute to one
 * reader and a second to another, and a load test that ran for the wrong duration produces
 * numbers that look entirely plausible.
 */
final class Durations {

    private static final Pattern PATTERN =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ns|us|ms|s|m|h)\\s*$", Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    /**
     * @param text something like {@code 250ms}
     * @param field which setting this is, for the error message
     * @return the duration
     */
    static Duration parse(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new ConfigException(field + " is empty; write a duration such as 30s or 500ms");
        }
        Matcher matcher = PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new ConfigException(field + ": '" + text + "' is not a duration."
                    + " Write a number and a unit: ns, us, ms, s, m or h — for example 30s."
                    + " A bare number is refused because it means different things to"
                    + " different readers.");
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);

        long nanos = switch (unit) {
            case "ns" -> (long) value;
            case "us" -> (long) (value * 1_000);
            case "ms" -> (long) (value * 1_000_000);
            case "s" -> (long) (value * 1_000_000_000L);
            case "m" -> (long) (value * 60_000_000_000L);
            case "h" -> (long) (value * 3_600_000_000_000L);
            default -> throw new ConfigException(field + ": unknown unit '" + unit + "'");
        };
        return Duration.ofNanos(nanos);
    }

    /** @return the duration written the way this class parses it */
    static String format(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos == 0) {
            return "0s";
        }
        if (nanos % 3_600_000_000_000L == 0) {
            return (nanos / 3_600_000_000_000L) + "h";
        }
        if (nanos % 60_000_000_000L == 0) {
            return (nanos / 60_000_000_000L) + "m";
        }
        if (nanos % 1_000_000_000L == 0) {
            return (nanos / 1_000_000_000L) + "s";
        }
        if (nanos % 1_000_000 == 0) {
            return (nanos / 1_000_000) + "ms";
        }
        if (nanos % 1_000 == 0) {
            return (nanos / 1_000) + "us";
        }
        return nanos + "ns";
    }
}
