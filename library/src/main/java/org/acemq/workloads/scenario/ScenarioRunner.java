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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.acemq.amqp.security.Security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts scenarios.
 *
 * <pre>{@code
 * ScenarioReport report = ScenarioRunner.run(scenario, "amqp://localhost");
 *
 * // or, for something that needs to watch and to stop:
 * ScenarioHandle handle = ScenarioRunner.start(scenario, "amqp://localhost", sample ->
 *         send(sample));
 * handle.stop();
 * ScenarioReport report = handle.report().join();
 * }</pre>
 */
public final class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private ScenarioRunner() {
    }

    /**
     * Runs it and waits.
     *
     * @param scenario what to run
     * @param brokerUrl an AMQP URL
     * @return what happened
     */
    public static ScenarioReport run(Scenario scenario, String brokerUrl) {
        return run(scenario, brokerUrl, null);
    }

    /**
     * Runs it against a broker that needs TLS, and waits.
     *
     * @param scenario what to run
     * @param brokerUrl an AMQPS URL
     * @param security the TLS policy: a private CA, a client certificate, or both
     * @return what happened
     */
    public static ScenarioReport run(Scenario scenario, String brokerUrl, Security security) {
        return new ScenarioRun(scenario, brokerUrl, security, ScenarioListener.NONE,
                new AtomicBoolean()).execute();
    }

    /**
     * Starts it on a thread of its own and reports as it goes.
     *
     * @param scenario what to run
     * @param brokerUrl an AMQP URL
     * @param listener told about each reading
     * @return a handle on the run
     */
    public static ScenarioHandle start(Scenario scenario, String brokerUrl,
            ScenarioListener listener) {
        return start(scenario, brokerUrl, null, listener);
    }

    /**
     * Starts it against a broker that needs TLS.
     *
     * @param scenario what to run
     * @param brokerUrl an AMQPS URL
     * @param security the TLS policy, or null for what the URL implies
     * @param listener told about each reading
     * @return a handle on the run
     */
    public static ScenarioHandle start(Scenario scenario, String brokerUrl, Security security,
            ScenarioListener listener) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(brokerUrl, "brokerUrl");
        Objects.requireNonNull(listener, "listener");

        AtomicBoolean stopRequested = new AtomicBoolean(false);
        CompletableFuture<ScenarioReport> report = new CompletableFuture<>();

        Thread thread = new Thread(() -> {
            try {
                ScenarioReport result = new ScenarioRun(
                        scenario, brokerUrl, security, listener, stopRequested).execute();
                try {
                    listener.onFinished(result);
                } catch (RuntimeException e) {
                    // The listener's problem, not the run's -- but logged rather than swallowed.
                    // A listener that throws here is one that will not have recorded the report,
                    // and silence turns that into a run that looks like it never ended.
                    log.warn("a listener threw while being told the run finished", e);
                }
                report.complete(result);
            } catch (Throwable failure) {
                try {
                    listener.onFailed(failure);
                } catch (RuntimeException e) {
                    log.warn("a listener threw while being told the run failed", e);
                }
                report.completeExceptionally(failure);
            }
        }, "scenario-" + scenario.name());
        // Not a daemon: a run holds a connection and the broker's queues, and letting the JVM
        // exit through the middle of one leaves the broker holding the mess.
        thread.setDaemon(false);
        thread.start();

        return new ScenarioHandle(scenario, report, stopRequested);
    }
}
