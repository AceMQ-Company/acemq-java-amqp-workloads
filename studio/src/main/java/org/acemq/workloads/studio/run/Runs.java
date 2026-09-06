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
package org.acemq.workloads.studio.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.acemq.workloads.Sample;
import org.acemq.workloads.scenario.Scenario;
import org.acemq.workloads.scenario.ScenarioHandle;
import org.acemq.workloads.scenario.ScenarioListener;
import org.acemq.workloads.scenario.ScenarioReport;
import org.acemq.workloads.scenario.ScenarioRunner;
import org.acemq.workloads.scenario.ScenarioSample;
import org.acemq.workloads.studio.scenario.ScenarioJson;
import org.acemq.workloads.studio.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The runs that are happening, and everyone watching them.
 *
 * <p>A run is started by an HTTP request and outlives it. The browser then subscribes to a stream
 * of readings, and may reconnect, close the tab, or open a second one — none of which should
 * touch the run. So the run belongs here and the streams are attached to it, rather than the run
 * belonging to whoever asked for it.
 *
 * <p><strong>One run at a time, by default.</strong> Two load generators sharing a machine measure
 * each other, and the numbers from both are worthless in a way that is not obvious afterwards.
 * Starting a second while one is going is refused with the id of the one already running.
 */
@Service
public class Runs {

    private static final Logger log = LoggerFactory.getLogger(Runs.class);

    private final RunStore store;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public Runs(RunStore store) {
        this.store = store;
    }

    /** A run in progress, with whoever is watching it. */
    private static final class Active {
        final String id;
        volatile ScenarioHandle handle;
        final List<SseEmitter> watchers = new CopyOnWriteArrayList<>();
        final List<ScenarioSample> samples = new CopyOnWriteArrayList<>();
        volatile Sample.Phase phase = Sample.Phase.STARTING;

        Active(String id) {
            this.id = id;
        }
    }

    /**
     * Starts one.
     *
     * @param scenarioId the saved scenario it came from, or null
     * @param file the scenario to run
     * @param brokerUrl where to run it
     * @return the run's identifier
     * @throws IllegalStateException if a run is already going
     */
    public String start(String scenarioId, ScenarioJson file, String brokerUrl) {
        if (!active.isEmpty()) {
            String running = active.keySet().iterator().next();
            throw new IllegalStateException(
                    "a run is already going (" + running + "). Two load generators on one machine"
                            + " measure each other, so stop that one first");
        }

        Scenario scenario = file.toScenario();
        List<String> problems = scenario.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }

        String id = UUID.randomUUID().toString();
        store.started(id, scenarioId, file, brokerUrl);

        // Registered before it is started. A short run can finish between start()
        // returning and the map being written, and then the entry put in afterwards
        // is one nothing will ever remove -- which shows up as a studio that
        // refuses to start anything ever again.
        Active run = new Active(id);
        active.put(id, run);

        ScenarioHandle handle = ScenarioRunner.start(scenario, brokerUrl, new ScenarioListener() {
            @Override
            public void onSample(ScenarioSample sample) {
                Active run = active.get(id);
                if (run == null) {
                    return;
                }
                run.samples.add(sample);
                store.sample(id, sample);
                broadcast(run, "sample", sample);
            }

            @Override
            public void onPhase(Sample.Phase phase) {
                Active run = active.get(id);
                if (run == null) {
                    return;
                }
                run.phase = phase;
                broadcast(run, "phase", Map.of("phase", phase.name()));
            }

            @Override
            public void onFinished(ScenarioReport report) {
                Active finished = active.get(id);
                try {
                    String verdict = !report.isValid() ? "invalid"
                            : report.passed() ? "passed" : "failed";
                    ReportJson body = ReportJson.of(report);
                    store.finished(id, verdict, body);
                    if (finished != null) {
                        broadcast(finished, "finished", body);
                    }
                } catch (RuntimeException e) {
                    // The run happened; only the recording of it failed. Saying so is
                    // better than a run that stays "running" for ever because storing
                    // its report threw.
                    log.error("run {} finished but its report could not be stored", id, e);
                    store.failed(id, e);
                    if (finished != null) {
                        broadcast(finished, "failed",
                                Map.of("error", "the run finished but its report could not be"
                                        + " stored: " + e.getMessage()));
                    }
                } finally {
                    if (finished != null) {
                        closeWatchers(finished);
                    }
                    // Always, whatever happened above. A studio that will not start a
                    // second run because the first one's bookkeeping threw is worse
                    // than one that loses a report.
                    active.remove(id);
                }
            }

            @Override
            public void onFailed(Throwable failure) {
                log.warn("run {} failed: {}", id, failure.toString());
                Active failed = active.get(id);
                try {
                    store.failed(id, failure);
                    if (failed != null) {
                        broadcast(failed, "failed",
                                Map.of("error", String.valueOf(failure.getMessage())));
                    }
                } finally {
                    if (failed != null) {
                        closeWatchers(failed);
                    }
                    active.remove(id);
                }
            }
        });

        run.handle = handle;
        return id;
    }

    /**
     * Ends a run early. It still reports on what it measured.
     *
     * @param id the run
     * @return whether there was one to stop
     */
    public boolean stop(String id) {
        Active run = active.get(id);
        if (run == null) {
            return false;
        }
        run.handle.stop();
        return true;
    }

    /** @return the run that is going, if one is */
    public Optional<String> current() {
        return active.keySet().stream().findFirst();
    }

    /**
     * Attaches a browser to a run.
     *
     * <p>Whatever has already been seen is replayed first, so a tab opened half way through a run
     * draws the whole chart rather than starting from the middle.
     *
     * @param id the run
     * @return the stream, or empty when that run is not going
     */
    public Optional<SseEmitter> watch(String id) {
        Active run = active.get(id);
        if (run == null) {
            return Optional.empty();
        }

        // No timeout: a run can be minutes long and a stream that expires under a watching user
        // is a chart that stops for no reason they can see.
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> run.watchers.remove(emitter));
        emitter.onTimeout(() -> run.watchers.remove(emitter));
        emitter.onError(error -> run.watchers.remove(emitter));

        try {
            emitter.send(SseEmitter.event().name("phase")
                    .data(Map.of("phase", run.phase.name())));
            for (ScenarioSample sample : run.samples) {
                emitter.send(SseEmitter.event().name("sample").data(sample));
            }
        } catch (IOException e) {
            // The browser went away between asking and being answered. Nothing to do about it,
            // and nothing worth logging.
            return Optional.of(emitter);
        }

        run.watchers.add(emitter);
        return Optional.of(emitter);
    }

    private void broadcast(Active run, String event, Object payload) {
        for (SseEmitter emitter : run.watchers) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // A closed tab. Drop the stream and carry on: the run does not care who is
                // watching, and one dead browser must not take the readings down.
                run.watchers.remove(emitter);
            }
        }
    }

    private void closeWatchers(Active run) {
        for (SseEmitter emitter : run.watchers) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // Already gone.
            }
        }
        run.watchers.clear();
    }
}
