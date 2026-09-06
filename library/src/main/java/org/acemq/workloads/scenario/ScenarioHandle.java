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
package org.acemq.workloads.scenario;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** A scenario in progress. */
public final class ScenarioHandle {

    private final Scenario scenario;
    private final CompletableFuture<ScenarioReport> report;
    private final AtomicBoolean stopRequested;

    ScenarioHandle(Scenario scenario, CompletableFuture<ScenarioReport> report,
            AtomicBoolean stopRequested) {
        this.scenario = scenario;
        this.report = report;
        this.stopRequested = stopRequested;
    }

    public Scenario scenario() {
        return scenario;
    }

    /**
     * Ends the run early and reports on what it measured.
     *
     * <p>Not an abort: producers stop, consumers finish what is in flight, and the report covers
     * however long the measured window actually lasted. It is marked as stopped early, so nobody
     * reads a twenty-second window as if it were the two minutes that were asked for.
     */
    public void stop() {
        stopRequested.set(true);
    }

    public boolean isStopping() {
        return stopRequested.get();
    }

    public boolean isRunning() {
        return !report.isDone();
    }

    /** @return the report, or an exceptional completion if the run could not be made */
    public CompletableFuture<ScenarioReport> report() {
        return report;
    }

    @Override
    public String toString() {
        return "ScenarioHandle{" + scenario.name() + ", "
                + (isRunning() ? "running" : "finished") + "}";
    }
}
