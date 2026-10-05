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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What a process is holding, read from outside it.
 *
 * <p>From outside because none of the five libraries reports any of this, and teaching all five
 * would measure what each believes about itself rather than what the operating system can see it
 * holding. A pid is a pid in every language, so a sixth client costs nothing here.
 */
public interface ProcessSampler {

    /** @param rssKb resident memory in kB @param fds open descriptors @param threads threads */
    record Usage(long rssKb, long fds, long threads) {
    }

    /** @return what the process holds, or empty if it is not running */
    Optional<Usage> sample(long pid);

    /** @return the sampler for this operating system: /proc on Linux, ps and lsof elsewhere */
    static ProcessSampler forThisOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") ? new Proc(Path.of("/proc")) : new PsLsof();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Linux: {@code /proc/<pid>/status} for VmRSS and Threads, and the entries of
     * {@code /proc/<pid>/fd} for descriptors. No subprocess per reading.
     */
    final class Proc implements ProcessSampler {
        private final Path root;

        Proc(Path root) {
            this.root = root;
        }

        @Override
        public Optional<Usage> sample(long pid) {
            Path dir = root.resolve(Long.toString(pid));
            long rss = 0;
            long threads = 0;
            try {
                for (String line : Files.readAllLines(dir.resolve("status"))) {
                    if (line.startsWith("VmRSS:")) {
                        rss = firstNumber(line);
                    } else if (line.startsWith("Threads:")) {
                        threads = firstNumber(line);
                    }
                }
            } catch (IOException e) {
                return Optional.empty();
            }
            long fds;
            try (Stream<Path> s = Files.list(dir.resolve("fd"))) {
                fds = s.count();
            } catch (IOException e) {
                fds = 0;
            }
            return Optional.of(new Usage(rss, fds, threads));
        }

        private static long firstNumber(String line) {
            String digits = line.replaceAll("[^0-9]+", " ").strip().split(" ")[0];
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        }
    }

    /**
     * macOS and other Unixes: {@code ps -o rss=}, {@code lsof -p} and {@code ps -M}, counted
     * exactly as {@code scripts/soak.sh} counted them so a report from either compares with the
     * other: every line lsof prints, header included, and every thread line ps prints after its
     * header.
     */
    final class PsLsof implements ProcessSampler {
        @Override
        public Optional<Usage> sample(long pid) {
            if (!alive(pid)) {
                return Optional.empty();
            }
            String p = Long.toString(pid);
            String rss = run(List.of("ps", "-o", "rss=", "-p", p)).strip();
            long fds = run(List.of("lsof", "-p", p)).lines().filter(l -> !l.isEmpty()).count();
            long threads = Math.max(0,
                    run(List.of("ps", "-M", p)).lines().skip(1).filter(l -> !l.isEmpty()).count());
            return Optional.of(new Usage(rss.matches("\\d+") ? Long.parseLong(rss) : 0, fds, threads));
        }

        private static String run(List<String> command) {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (InputStream in = process.getInputStream()) {
                    String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    process.waitFor(30, TimeUnit.SECONDS);
                    return out;
                }
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }
}
