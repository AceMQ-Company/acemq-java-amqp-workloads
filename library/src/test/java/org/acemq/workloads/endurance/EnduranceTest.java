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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run loop, the launcher and the sampler, with fakes where the outside world would be. */
class EnduranceTest {

    @TempDir
    Path ws;

    private EnduranceConfig config(String... clients) {
        EnduranceConfig c = new EnduranceConfig();
        c.workspace = ws.toString();
        c.clients = new ArrayList<>(List.of(clients));
        c.cycles = 4;
        c.cycleSeconds = 0;
        c.warmupSeconds = 0;
        c.sampleSeconds = 0;
        c.cooldownSeconds = 0;
        c.readingGapSeconds = 0;
        c.startupSeconds = 1;
        return c;
    }

    private static EnduranceConfig.Launch command(String... argv) {
        EnduranceConfig.Launch l = new EnduranceConfig.Launch();
        l.command = List.of(argv);
        return l;
    }

    @Nested
    class Launcher {

        @Test
        void startsWritesThePidFileAndStopsWhatItStarted() throws Exception {
            EnduranceConfig c = config("fake");
            c.launch.put("fake", command("sh", "-c", "echo '{\"published\":1}'; exec sleep 60"));
            ClientLauncher launcher = new ClientLauncher(c, s -> { });

            List<ClientLauncher.Client> clients = launcher.start();

            ClientLauncher.Client fake = clients.get(0);
            assertThat(fake.launched()).isTrue();
            assertThat(ClientLauncher.alive(fake.pid())).isTrue();
            assertThat(Files.readString(ws.resolve(".chaos/workload-fake.pid")).strip())
                    .isEqualTo(Long.toString(fake.pid()));
            assertThat(Files.readString(ws.resolve(".chaos/workload-fake.jsonl")))
                    .contains("\"published\":1");

            launcher.stop(clients);
            assertThat(ClientLauncher.alive(fake.pid())).isFalse();
            assertThat(ws.resolve(".chaos/workload-fake.pid")).doesNotExist();
        }

        @Test
        void adoptsALoadAlreadyRunningAndLeavesItAlone() throws Exception {
            EnduranceConfig c = config("fake");
            c.launch.put("fake", command("false"));
            Files.createDirectories(ws.resolve(".chaos"));
            long me = ProcessHandle.current().pid();
            Files.writeString(ws.resolve(".chaos/workload-fake.pid"), me + "\n");
            ClientLauncher launcher = new ClientLauncher(c, s -> { });

            List<ClientLauncher.Client> clients = launcher.start();
            assertThat(clients).singleElement()
                    .satisfies(x -> assertThat(x.pid()).isEqualTo(me))
                    .satisfies(x -> assertThat(x.launched()).isFalse());
            launcher.stop(clients);
            assertThat(ws.resolve(".chaos/workload-fake.pid")).exists();
        }

        @Test
        void aLoadThatDiesStraightAwayIsAFailureToStartWithItsOutput() {
            EnduranceConfig c = config("fake");
            c.launch.put("fake", command("sh", "-c", "echo no such vhost; exit 3"));
            assertThatThrownBy(() -> new ClientLauncher(c, s -> { }).start())
                    .isInstanceOf(ClientLauncher.LaunchException.class)
                    .hasMessageContaining("exited immediately")
                    .hasMessageContaining("no such vhost");
        }

        @Test
        void aFailedBuildStopsBeforeAnythingStarts() {
            EnduranceConfig c = config("fake");
            EnduranceConfig.Launch l = command("sleep", "60");
            l.build = List.of("sh", "-c", "echo compile error; exit 1");
            c.launch.put("fake", l);
            assertThatThrownBy(() -> new ClientLauncher(c, s -> { }).start())
                    .hasMessageContaining("the build failed")
                    .hasMessageContaining("compile error");
        }

        @Test
        void placeholdersAndEnvironmentReachTheProcess() throws Exception {
            EnduranceConfig c = config("fake");
            c.broker = "amqp://u:p@h:1/";
            EnduranceConfig.Launch l = command("sh", "-c", "echo \"$URL|$1\"; exec sleep 60", "x", "${workspace}");
            l.env = Map.of("URL", "${broker}/v");
            c.launch.put("fake", l);
            ClientLauncher launcher = new ClientLauncher(c, s -> { });
            List<ClientLauncher.Client> clients = launcher.start();
            launcher.stop(clients);
            assertThat(Files.readString(ws.resolve(".chaos/workload-fake.jsonl")).strip())
                    .isEqualTo("amqp://u:p@h:1/v|" + ws.toAbsolutePath().normalize());
        }

        @Test
        void theDefaultLaunchesAreTheFiveTheDrillStarts() {
            EnduranceConfig c = new EnduranceConfig();
            for (String lang : EnduranceConfig.LANGUAGES) {
                assertThat(c.launchFor(lang).command).isNotEmpty();
                assertThat(c.launchFor(lang).env).containsEntry("ACEMQ_EXAMPLE_SECONDS", "0");
            }
            // The pid recorded must be the publisher's, never a launcher that execs it.
            assertThat(c.launchFor("ruby").command).doesNotContain("bundle", "exec");
            assertThat(c.launchFor("go").command.get(0)).isEqualTo("${state}/standing-load-go");
            assertThat(c.launchFor("dotnet").command.get(0)).doesNotEndWith("dotnet");
        }
    }

    @Nested
    class Sampler {

        @Test
        void procReadsStatusAndCountsDescriptors() throws IOException {
            Path proc = ws.resolve("proc");
            Path pid = Files.createDirectories(proc.resolve("42/fd"));
            Files.writeString(pid.getParent().resolve("status"),
                    "Name:\tjava\nVmRSS:\t  123456 kB\nThreads:\t46\n");
            for (int i = 0; i < 7; i++) {
                Files.createFile(pid.resolve(Integer.toString(i)));
            }
            assertThat(new ProcessSampler.Proc(proc).sample(42))
                    .contains(new ProcessSampler.Usage(123456, 7, 46));
            assertThat(new ProcessSampler.Proc(proc).sample(43)).isEmpty();
        }

        @Test
        void thisOsCanReadThisProcess() {
            Optional<ProcessSampler.Usage> me = ProcessSampler.forThisOs().sample(ProcessHandle.current().pid());
            assertThat(me).hasValueSatisfying(u -> {
                assertThat(u.rssKb()).isPositive();
                assertThat(u.threads()).isPositive();
                assertThat(u.fds()).isPositive();
            });
        }

        @Test
        void aProcessThatIsGoneHasNoReading() throws Exception {
            Process p = new ProcessBuilder("true").start();
            p.waitFor();
            assertThat(ProcessSampler.forThisOs().sample(p.pid())).isEmpty();
        }
    }

    @Nested
    class Run {

        @Test
        void writesTheReportTheReadingsAndTheJsonAndClosesOnceACycle() throws Exception {
            EnduranceConfig c = config("fake");
            c.launch.put("fake", command("sh", "-c",
                    "i=0; while true; do i=$((i+1)); echo \"{\\\"published\\\":$i,\\\"consumed\\\":$i}\"; sleep 0.2; done"));
            c.cycleSeconds = 1;
            c.sampleSeconds = 2;
            AtomicInteger closes = new AtomicInteger();
            AtomicInteger growth = new AtomicInteger();
            Endurance.Fault fault = new Endurance.Fault() {
                boolean justClosed;

                @Override
                public void closeAll(String reason) {
                    closes.incrementAndGet();
                    justClosed = true;
                }

                @Override
                public long connections() {
                    long n = justClosed ? 0 : 1;
                    justClosed = false;
                    return n;
                }
            };
            ProcessSampler sampler = pid -> Optional.of(
                    new ProcessSampler.Usage(1000 + growth.incrementAndGet(), 10, 5));
            List<String> phases = new ArrayList<>();
            Endurance.Listener listener = new Endurance.Listener() {
                @Override
                public void phase(String phase) {
                    phases.add(phase);
                }
            };

            Endurance.Result r = new Endurance(c, sampler, fault, listener).run();

            assertThat(closes).hasValue(4);
            assertThat(phases).containsExactly("starting", "warmup", "baseline", "cycling",
                    "cooldown", "final", "verdict", "done");
            assertThat(r.verdict().passed()).isTrue();
            assertThat(r.report()).isEqualTo(ws.toAbsolutePath().normalize()
                    .resolve("reports/soak-" + r.stamp() + ".md"));
            assertThat(Files.readString(r.report()))
                    .contains("**PASSED**")
                    .contains("| fake | 0MB | 0MB | 10 | 10 | 5 | 5 | clean |")
                    .contains("connections immediately after a close: low 0, high 0, over 4 cycles")
                    .endsWith("Readings: `.chaos/soak-" + r.stamp() + ".tsv`\n");
            List<String> tsv = Files.readAllLines(r.readings());
            assertThat(tsv.get(0)).isEqualTo(Reading.HEADER);
            assertThat(tsv).hasSize(1 + 3 + 2 + 3);
            assertThat(Files.readAllLines(r.readings().resolveSibling("soak-" + r.stamp() + ".closes")))
                    .containsExactly("0", "0", "0", "0");
            JsonNode json = new ObjectMapper().readTree(r.json().toFile());
            assertThat(json.path("verdict").asText()).isEqualTo("PASSED");
            assertThat(json.path("source").asText()).isEqualTo("framework");
            assertThat(json.path("clients").get(0).path("library").asText()).isEqualTo("fake");
            assertThat(json.path("series").path("fake")).hasSize(8);
            // and the load it started is stopped
            assertThat(ws.resolve(".chaos/workload-fake.pid")).doesNotExist();
        }
    }

    @Nested
    class Cli {

        @Test
        void flagsOverrideTheFileAndTheFileOverridesTheDefaults() throws IOException {
            Path yaml = ws.resolve("endurance.yaml");
            Files.writeString(yaml, """
                    cycles: 60
                    cycleSeconds: 30
                    clients: [java, go]
                    allowances: { threads: 20 }
                    launch:
                      go: { env: { EXTRA: "1" } }
                    """);
            EnduranceCli.Parsed p = EnduranceCli.parse(new String[] {
                "-f", yaml.toString(), "--cycles", "10", "--clients", "python,ruby", "--keep"});
            assertThat(p.config().cycles).isEqualTo(10);
            assertThat(p.config().cycleSeconds).isEqualTo(30);
            assertThat(p.config().warmupSeconds).isEqualTo(120);
            assertThat(p.config().clients).containsExactly("python", "ruby");
            assertThat(p.config().allowances.threads).isEqualTo(20);
            assertThat(p.config().allowances.descriptors).isEqualTo(16);
            assertThat(p.config().keep).isTrue();
            // a launch override is laid over the default, not instead of it
            assertThat(p.config().launchFor("go").env).containsEntry("EXTRA", "1")
                    .containsEntry("ACEMQ_EXAMPLE_SECONDS", "0");
            assertThat(p.config().launchFor("go").build).isNotEmpty();
        }

        @Test
        void aMistakeExitsTwoBeforeAnythingStarts() {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
            PrintStream o = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            assertThat(EnduranceCli.run(new String[] {"--cycles", "many"}, o, e))
                    .isEqualTo(EnduranceCli.COULD_NOT_RUN);
            assertThat(EnduranceCli.run(new String[] {"--clients", "cobol"}, o, e))
                    .isEqualTo(EnduranceCli.COULD_NOT_RUN);
            assertThat(EnduranceCli.run(new String[] {"--bogus"}, o, e))
                    .isEqualTo(EnduranceCli.COULD_NOT_RUN);
            assertThat(err.toString(StandardCharsets.UTF_8))
                    .contains("--cycles must be a whole number")
                    .contains("no launch command for client 'cobol'")
                    .contains("unknown argument --bogus");
        }

        @Test
        void anUnknownKeyInTheFileIsRefused() throws IOException {
            Path yaml = ws.resolve("bad.yaml");
            Files.writeString(yaml, "cylces: 10\n");
            assertThatThrownBy(() -> EnduranceConfig.read(yaml))
                    .hasMessageContaining("cylces");
        }
    }
}
