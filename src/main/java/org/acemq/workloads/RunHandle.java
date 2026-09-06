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
package org.acemq.workloads;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A run in progress.
 *
 * <p>Returned by {@link Workload#start(String, RunListener)}, for anything that cannot sit and
 * wait for {@link Workload#run(String)} to return: a user interface, a scheduler, a test that
 * wants to stop a run once it has seen enough.
 */
public final class RunHandle {

    private final Workload workload;
    private final CompletableFuture<WorkloadReport> report;
    private final AtomicBoolean stopRequested;

    RunHandle(Workload workload, CompletableFuture<WorkloadReport> report,
            AtomicBoolean stopRequested) {
        this.workload = workload;
        this.report = report;
        this.stopRequested = stopRequested;
    }

    /** @return the workload being run */
    public Workload workload() {
        return workload;
    }

    /**
     * Ends the run early and reports on what it measured.
     *
     * <p><strong>Not an abort.</strong> Publishers stop, consumers are given their usual moment to
     * finish what is in flight, and a report is produced covering however long the measured phase
     * actually lasted. That report is honest rather than empty — and a run stopped after twenty
     * seconds trips the {@code run-was-long-enough} rule, which is exactly what should happen.
     *
     * <p>Stopping during warm-up produces a report over an empty measured window. Calling this
     * twice does nothing the second time.
     */
    public void stop() {
        stopRequested.set(true);
    }

    /** @return whether somebody has asked this run to stop */
    public boolean isStopping() {
        return stopRequested.get();
    }

    /** @return whether the run is still going */
    public boolean isRunning() {
        return !report.isDone();
    }

    /**
     * The report, when there is one.
     *
     * <p>Completes exceptionally if the run could not be made: a broker that cannot be reached, a
     * queue the broker refuses to declare. A run that was stopped early completes normally.
     *
     * @return a future for the report
     */
    public CompletableFuture<WorkloadReport> report() {
        return report;
    }

    @Override
    public String toString() {
        return "RunHandle{" + workload.name() + ", " + (isRunning() ? "running" : "finished") + "}";
    }
}
