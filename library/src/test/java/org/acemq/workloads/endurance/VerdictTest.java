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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerdictTest {

    private static final EnduranceConfig.Allowances DEFAULTS = new EnduranceConfig.Allowances();

    /**
     * Reports {@code scripts/soak.sh} wrote, from its own readings: a pass, a failure with a known
     * upstream breach beside it, and a load that died. The port has to write each one again byte
     * for byte, because release-preflight.sh reads these files and nothing about it changes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"20261005-163130", "20261003-175206", "20261001-021301"})
    void rewritesWhatTheScriptWroteFromTheSameReadings(String stamp) throws IOException {
        String golden = resource("soak-" + stamp + ".md");
        Matcher m = Pattern.compile("(?m)^(\\d+) cycles, one every (\\d+)s, sampling every (\\d+)s")
                .matcher(golden);
        assertThat(m.find()).isTrue();
        int closed = Integer.parseInt(m.group(1));

        Verdict v = Verdict.of(readings(resource("soak-" + stamp + ".tsv")),
                closes(resource("soak-" + stamp + ".closes")), closed, DEFAULTS,
                EnduranceConfig.KnownUpstream.defaults());

        assertThat(v.markdown(stamp, closed, Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), DEFAULTS, ".chaos/soak-" + stamp + ".tsv"))
                .isEqualTo(golden);
    }

    /**
     * What {@code check_soak} in release-preflight.sh does with a report, in its own patterns: a
     * {@code **FAILED**} line, then the {@code - } bullets naming the language; or a
     * {@code **PASSED**} line with the known breach under "## Known upstream".
     */
    @Nested
    class WhatTheReleaseGateReads {

        @Test
        void aFailureNamesTheLanguageItBelongsTo() throws IOException {
            String report = resource("soak-20261003-175206.md");
            assertThat(report).containsPattern("(?m)^\\*\\*FAILED\\*\\*$");
            List<String> bullets = report.lines().filter(l -> l.startsWith("- ")).toList();
            assertThat(bullets).anyMatch(l -> Pattern.compile("(^| )ruby( |,|$|')").matcher(l).find());
            assertThat(bullets).noneMatch(l -> Pattern.compile("(^| )java( |,|$|')").matcher(l).find());
        }

        @Test
        void aPassWithAKnownBreachListsItUnderItsOwnHeading() {
            Verdict v = Verdict.of(ruby(10, 200, 43_000, 60_000), List.of(2L), 240, DEFAULTS,
                    EnduranceConfig.KnownUpstream.defaults());
            String md = v.markdown("s", 240, 15, 30, DEFAULTS, "r.tsv");
            assertThat(md).containsPattern("(?m)^\\*\\*PASSED\\*\\*$");
            String known = md.substring(md.indexOf("## Known upstream"));
            assertThat(known.lines().filter(l -> l.startsWith("- ")))
                    .singleElement().asString().startsWith("- ruby threads grew from 10 to 200");
        }
    }

    @Nested
    class KnownUpstream {

        @Test
        void growthInsideTheMeasuredCeilingPassesAndIsReported() {
            // 10 + int(1.05 * 240) + 16 = 278
            Verdict v = Verdict.of(ruby(10, 278, 43_000, 43_000), List.of(2L), 240, DEFAULTS,
                    EnduranceConfig.KnownUpstream.defaults());
            assertThat(v.passed()).isTrue();
            assertThat(v.rows().get(0).verdict()).isEqualTo("thread growth (known)");
            assertThat(v.known()).singleElement().asString()
                    .contains("known, bunny leaks a consumer work pool")
                    .endsWith("(up to 278 is allowed for it)");
        }

        @Test
        void growthPastTheCeilingFailsLikeAnythingElse() {
            Verdict v = Verdict.of(ruby(10, 279, 43_000, 43_000), List.of(2L), 240, DEFAULTS,
                    EnduranceConfig.KnownUpstream.defaults());
            assertThat(v.passed()).isFalse();
            assertThat(v.failures()).containsExactly(
                    "ruby threads grew from 10 to 279, more than the 16 allowed");
            assertThat(v.rows().get(0).verdict()).isEqualTo("thread growth");
        }

        @Test
        void memoryHasACeilingOfItsOwn() {
            Verdict inside = Verdict.of(ruby(10, 10, 44_000, 132_000), List.of(2L), 240, DEFAULTS,
                    EnduranceConfig.KnownUpstream.defaults());
            assertThat(inside.passed()).isTrue();
            assertThat(inside.known()).singleElement().asString()
                    .startsWith("ruby resident memory grew from 42MB to 128MB, more than 2x the baseline")
                    .endsWith("(up to 128MB is allowed for it)");

            Verdict past = Verdict.of(ruby(10, 10, 44_000, 132_001), List.of(2L), 240, DEFAULTS,
                    EnduranceConfig.KnownUpstream.defaults());
            assertThat(past.passed()).isFalse();
        }

        @Test
        void anotherLanguageWithTheSameGrowthIsNotExcused() {
            List<Reading> r = new ArrayList<>();
            for (Reading x : ruby(10, 100, 43_000, 43_000)) {
                r.add(new Reading(x.epoch(), x.phase(), "go", x.rssKb(), x.fds(), x.threads(),
                        x.published(), x.consumed(), x.connections()));
            }
            Verdict v = Verdict.of(r, List.of(2L), 240, DEFAULTS, EnduranceConfig.KnownUpstream.defaults());
            assertThat(v.failures()).containsExactly("go threads grew from 10 to 100, more than the 16 allowed");
            assertThat(v.known()).isEmpty();
        }
    }

    @Nested
    class Allowances {

        @Test
        void descriptorsAndThreadsGetAnAbsoluteSlackAndMemoryAMultiple() {
            List<Reading> r = List.of(
                    reading("baseline", "java", 1000, 30, 40, 1, 1),
                    reading("final", "java", 2000, 46, 56, 2, 2));
            assertThat(Verdict.of(r, List.of(0L), 1, DEFAULTS, List.of()).passed()).isTrue();

            List<Reading> over = List.of(
                    reading("baseline", "java", 1000, 30, 40, 1, 1),
                    reading("final", "java", 2001, 47, 57, 2, 2));
            Verdict v = Verdict.of(over, List.of(0L), 1, DEFAULTS, List.of());
            assertThat(v.rows().get(0).verdict()).isEqualTo("descriptor growth, thread growth, memory growth");
        }

        @Test
        void theMedianOfThreeReadingsIgnoresOneOutlier() {
            List<Reading> r = List.of(
                    reading("baseline", "go", 1000, 10, 10, 1, 1),
                    reading("baseline", "go", 9000, 10, 10, 1, 1),
                    reading("baseline", "go", 1100, 10, 10, 1, 1),
                    reading("final", "go", 2100, 10, 10, 2, 2),
                    reading("final", "go", 1, 10, 10, 2, 2),
                    reading("final", "go", 2150, 10, 10, 2, 2));
            Verdict v = Verdict.of(r, List.of(0L), 1, DEFAULTS, List.of());
            assertThat(v.rows().get(0).rssBefore()).isEqualTo(1100);
            assertThat(v.rows().get(0).rssAfter()).isEqualTo(2100);
            assertThat(v.passed()).isTrue();
        }

        @Test
        void aLoadThatStoppedPublishingFailsEvenWithFlatResources() {
            List<Reading> r = List.of(
                    reading("baseline", "python", 1000, 10, 2, 50, 50),
                    reading("final", "python", 1000, 10, 2, 50, 70));
            Verdict v = Verdict.of(r, List.of(0L), 1, DEFAULTS, List.of());
            assertThat(v.failures()).containsExactly(
                    "python stopped working during the soak: published 50->50, consumed 50->70");
        }

        @Test
        void cyclesThatClosedNothingFailTheRun() {
            List<Reading> r = List.of(
                    reading("baseline", "go", 1000, 10, 10, 1, 1),
                    reading("final", "go", 1000, 10, 10, 2, 2));
            Verdict v = Verdict.of(r, List.of(6L, 6L), 2, DEFAULTS, List.of());
            assertThat(v.failures()).singleElement().asString()
                    .startsWith("closing connections never removed any: 6 still open");
            assertThat(Verdict.of(r, List.of(), 0, DEFAULTS, List.of()).failures())
                    .containsExactly("no cycle recorded a connection count, so nothing proves"
                            + " the clients ever recovered");
        }

        @Test
        void aFractionalMemoryFactorIsWrittenAsPythonWroteIt() {
            assertThat(Verdict.number(2.0)).isEqualTo("2");
            assertThat(Verdict.number(2.5)).isEqualTo("2.5");
        }
    }

    private static List<Reading> ruby(long threadsBefore, long threadsAfter, long rssBefore, long rssAfter) {
        return List.of(
                new Reading(1, "baseline", "ruby", rssBefore, 29, threadsBefore, 10, 10, 6),
                new Reading(2, "final", "ruby", rssAfter, 29, threadsAfter, 20, 20, 6));
    }

    private static Reading reading(String phase, String lang, long rss, long fds, long threads,
            long published, long consumed) {
        return new Reading(1, phase, lang, rss, fds, threads, published, consumed, 6);
    }

    static List<Reading> readings(String tsv) {
        return tsv.lines().map(Reading::parse).filter(Objects::nonNull).toList();
    }

    static List<Long> closes(String text) {
        return text.lines().map(String::strip).filter(l -> l.matches("-?\\d+")).map(Long::parseLong).toList();
    }

    static String resource(String name) throws IOException {
        try (InputStream in = VerdictTest.class.getResourceAsStream("/endurance/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
