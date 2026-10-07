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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code loads up|down|status} with fake processes standing in for the five loads. */
class LoadsCliTest {

    @TempDir
    Path ws;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** A load that prints a banner, then a sample every 0.2s, as the real ones do. */
    private static final String SAMPLING = "echo banner; i=0; while true; do i=$((i+1)); "
            + "echo \"{\\\"at\\\":\\\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\\\",\\\"published\\\":$i,"
            + "\\\"publishRate\\\":5.0,\\\"consumeRate\\\":4.0}\"; sleep 0.2; done";

    @AfterEach
    void down() {
        LoadsCli.run(new String[] {"down", "--workspace", ws.toString(), "-f", yaml("fake", "true").toString(),
                "--clients", "fake"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
    }

    private Path yaml(String client, String script) {
        Path f = ws.resolve("loads.yaml");
        try {
            Files.writeString(f, """
                    startupSeconds: 3
                    clients: [%s]
                    launch:
                      %s:
                        command: [sh, -c, '%s']
                    """.formatted(client, client, script.replace("'", "''")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return f;
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        String[] full = new String[args.length + 2];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = "--workspace";
        full[args.length + 1] = ws.toString();
        return LoadsCli.run(full, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void upWaitsForTheFirstSampleStatusReportsItAndDownStopsIt() throws Exception {
        Path f = yaml("fake", "sleep 0.5; " + SAMPLING);

        assertThat(run("up", "-f", f.toString())).as(said()).isZero();
        assertThat(said()).contains("fake: running (pid ");
        long pid = Long.parseLong(Files.readString(ws.resolve(".chaos/workload-fake.pid")).strip());
        assertThat(ClientLauncher.alive(pid)).isTrue();
        assertThat(ClientLauncher.lastSample(ws.resolve(".chaos/workload-fake.jsonl"))).isPresent();

        assertThat(run("status", "-f", f.toString(), "--json")).isZero();
        JsonNode s = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        JsonNode fake = s.path("clients").get(0);
        assertThat(fake.path("client").asText()).isEqualTo("fake");
        assertThat(fake.path("running").asBoolean()).isTrue();
        assertThat(fake.path("pid").asLong()).isEqualTo(pid);
        assertThat(fake.path("publishRate").asDouble()).isEqualTo(5.0);
        assertThat(fake.path("consumeRate").asDouble()).isEqualTo(4.0);
        assertThat(fake.path("lastSampleAgeSeconds").asDouble()).isLessThan(10);

        assertThat(run("status", "-f", f.toString())).isZero();
        assertThat(said()).containsPattern("fake +running +" + pid + " +\\d+\\.\\ds ago +5\\.0 +4\\.0");

        assertThat(run("down", "-f", f.toString())).isZero();
        assertThat(said()).contains("fake: stopped");
        assertThat(ClientLauncher.alive(pid)).isFalse();
        assertThat(ws.resolve(".chaos/workload-fake.pid")).doesNotExist();

        assertThat(run("status", "-f", f.toString(), "--json")).isZero();
        fake = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8)).path("clients").get(0);
        assertThat(fake.path("running").asBoolean()).isFalse();
        assertThat(fake.path("pid").isNull()).isTrue();

        assertThat(run("down", "-f", f.toString())).as("nothing running is still success").isZero();
        assertThat(said()).contains("fake: nothing to stop");
    }

    @Test
    void endurancesLauncherAdoptsWhatLoadsUpStarted() throws Exception {
        Path f = yaml("fake", SAMPLING);
        assertThat(run("up", "-f", f.toString())).as(said()).isZero();
        long pid = Long.parseLong(Files.readString(ws.resolve(".chaos/workload-fake.pid")).strip());

        EnduranceConfig c = EnduranceConfig.read(f);
        c.workspace = ws.toString();
        var clients = new ClientLauncher(c, m -> { }).start();
        assertThat(clients).singleElement()
                .satisfies(x -> assertThat(x.pid()).isEqualTo(pid))
                .satisfies(x -> assertThat(x.launched()).isFalse());
    }

    @Test
    void upAdoptsALiveLoadAndDownStopsItByItsPidFile() throws Exception {
        Path f = yaml("fake", "exit 1");
        Process other = new ProcessBuilder("sleep", "60").start();
        Files.createDirectories(ws.resolve(".chaos"));
        Files.writeString(ws.resolve(".chaos/workload-fake.pid"), other.pid() + "\n");
        Files.writeString(ws.resolve(".chaos/workload-fake.jsonl"), "{\"published\":1}\n");

        assertThat(run("up", "-f", f.toString())).as(said()).isZero();
        assertThat(said()).contains("fake: already running (pid " + other.pid() + ")");

        assertThat(run("down", "-f", f.toString())).isZero();
        assertThat(other.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aLoadThatNeverSamplesIsATimeoutNamingItWithItsOutputAndIsStopped() throws Exception {
        Path f = yaml("fake", "echo connecting to nowhere; exec sleep 60");

        assertThat(run("up", "-f", f.toString())).isEqualTo(LoadsCli.COULD_NOT);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("fake: no sample within 3s")
                .contains("connecting to nowhere");
        assertThat(ws.resolve(".chaos/workload-fake.pid")).doesNotExist();
    }

    @Test
    void aLoadThatExitsIsACouldNotStartWithItsOutput() {
        Path f = yaml("fake", "echo no such vhost; exit 3");
        assertThat(run("up", "-f", f.toString())).isEqualTo(LoadsCli.COULD_NOT);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("fake: the standing load exited before its first sample")
                .contains("no such vhost");
    }

    @Test
    void downRemovesAStalePidFile() throws Exception {
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Files.createDirectories(ws.resolve("st"));
        Files.writeString(ws.resolve("st/workload-java.pid"), gone.pid() + "\n");

        assertThat(run("down", "--state", "st", "--clients", "java")).isZero();
        assertThat(said()).contains("java: nothing to stop (removed a stale pid file)");
        assertThat(ws.resolve("st/workload-java.pid")).doesNotExist();
    }

    @Test
    void theDefaultsAreTheFiveAndTheStateDirectory() throws Exception {
        assertThat(run("status", "--json")).isZero();
        JsonNode s = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(s.path("state").asText()).isEqualTo(ws.toAbsolutePath().normalize().resolve(".chaos").toString());
        assertThat(s.path("clients").findValuesAsText("client"))
                .containsExactly("java", "go", "python", "ruby", "dotnet");
    }

    @Test
    void aBadCommandOrFlagCouldNotRun() {
        assertThat(run("sideways")).isEqualTo(LoadsCli.COULD_NOT);
        assertThat(run("up", "--bogus")).isEqualTo(LoadsCli.COULD_NOT);
        assertThat(run("up", "--clients", "cobol")).isEqualTo(LoadsCli.COULD_NOT);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no launch command for client 'cobol'");
    }
}
