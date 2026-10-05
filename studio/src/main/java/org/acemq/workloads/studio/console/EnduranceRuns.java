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
package org.acemq.workloads.studio.console;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PreDestroy;

import org.acemq.workloads.endurance.ClientLauncher;
import org.acemq.workloads.endurance.Endurance;
import org.acemq.workloads.endurance.EnduranceConfig;
import org.acemq.workloads.endurance.ProcessSampler;
import org.acemq.workloads.endurance.Reading;
import org.acemq.workloads.endurance.Verdict;
import org.acemq.workloads.studio.run.Runs;
import org.acemq.workloads.studio.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * A soak started from the console: the library's {@link Endurance} run, in this process, against
 * the drill workspace, with its progress kept for the Endurance view to poll.
 *
 * <p>One at a time, and never beside a load or a scenario run: a soak measures what processes on
 * this machine hold, and a second load generator would be measured with them.
 */
@Service
public class EnduranceRuns {

    private static final Logger log = LoggerFactory.getLogger(EnduranceRuns.class);

    private final ConsoleRuns loads;
    private final Runs scenarioRuns;
    private volatile Live live;

    public EnduranceRuns(@Lazy ConsoleRuns loads, @Lazy Runs scenarioRuns) {
        this.loads = loads;
        this.scenarioRuns = scenarioRuns;
    }

    /** What the soak is doing, read by the view every couple of seconds. */
    static final class Live {
        final Instant started = Instant.now();
        final int cycles;
        final List<Reading> readings = new ArrayList<>();
        volatile Thread thread;
        volatile String phase = "starting";
        volatile int cycle;
        volatile String said;
        volatile String error;
        volatile String verdict;
        volatile String report;

        Live(int cycles) {
            this.cycles = cycles;
        }

        boolean running() {
            Thread t = thread;
            return t != null && t.isAlive();
        }
    }

    /** @return whether a soak started here is still going */
    public boolean isRunning() {
        Live l = live;
        return l != null && l.running();
    }

    /**
     * @param config the run, already pointed at the drill workspace
     * @throws IllegalStateException if anything else is running
     */
    synchronized void start(EnduranceConfig config) {
        if (isRunning()) {
            throw new IllegalStateException("a soak is already running. One at a time: two would"
                    + " close each other's connections and measure each other's loads");
        }
        if (loads.isRunning()) {
            throw new IllegalStateException("a load is running from the console. A soak measures"
                    + " what processes on this machine hold; stop the load first");
        }
        if (scenarioRuns.current().isPresent()) {
            throw new IllegalStateException("a scenario run is going in the studio. A soak"
                    + " measures what processes on this machine hold; stop it first");
        }
        config.validate();
        Live run = new Live(config.cycles);
        Endurance.Listener listener = new Endurance.Listener() {
            @Override
            public void say(String message) {
                run.said = message;
            }

            @Override
            public void phase(String phase) {
                run.phase = phase;
            }

            @Override
            public void cycle(int done, int of) {
                run.cycle = done;
            }

            @Override
            public void reading(Reading reading) {
                synchronized (run.readings) {
                    run.readings.add(reading);
                }
            }
        };
        Thread t = new Thread(() -> {
            try (Endurance.Fault fault = Endurance.managementFault(config)) {
                Endurance.Result r = new Endurance(config, ProcessSampler.forThisOs(), fault, listener).run();
                run.verdict = r.verdict().status();
                run.report = r.report().toString();
            } catch (InterruptedException e) {
                run.error = "stopped from the console before the verdict; no report was written";
                run.phase = "stopped";
            } catch (ClientLauncher.LaunchException e) {
                run.error = e.getMessage();
                run.phase = "failed";
            } catch (Exception e) {
                log.error("the soak failed", e);
                run.error = RunStore.redact(String.valueOf(e.getMessage()));
                run.phase = "failed";
            }
        }, "endurance");
        run.thread = t;
        live = run;
        t.start();
    }

    boolean stop() {
        Live l = live;
        if (l == null || !l.running()) {
            return false;
        }
        l.thread.interrupt();
        return true;
    }

    /** @return the soak's progress: phase, cycle, readings so far as chart series */
    Map<String, Object> progress() {
        Live l = live;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", l != null && l.running());
        if (l == null) {
            return out;
        }
        out.put("startedAt", l.started.toString());
        out.put("phase", l.phase);
        out.put("cycle", l.cycle);
        out.put("cycles", l.cycles);
        out.put("said", l.said);
        out.put("error", l.error);
        out.put("verdict", l.verdict);
        out.put("report", l.report == null ? null : Path.of(l.report).getFileName().toString());
        List<Reading> copy;
        synchronized (l.readings) {
            copy = List.copyOf(l.readings);
        }
        out.put("series", Verdict.series(copy));
        return out;
    }

    @PreDestroy
    void stopOnShutdown() throws InterruptedException {
        Live l = live;
        if (l != null && l.running()) {
            l.thread.interrupt();
            l.thread.join(30_000);
        }
    }
}
