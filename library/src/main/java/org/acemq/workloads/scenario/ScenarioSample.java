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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.acemq.workloads.Sample;
import org.acemq.workloads.metrics.LatencySummary;

/**
 * One reading of a whole scenario, node by node.
 *
 * <p>A single pair of numbers cannot describe a graph. Total throughput looks healthy while one
 * leg of a fan-out is falling behind, and the moment worth seeing is exactly the one where two
 * queues stop agreeing with each other — so every queue and every producer reports separately.
 *
 * @param at when this reading was taken
 * @param elapsed how long the run has been going
 * @param phase what the run was doing
 * @param producers a reading per producer
 * @param queues a reading per queue
 * @param blocked whether the broker is refusing publishes for a resource alarm
 */
public record ScenarioSample(
        Instant at,
        Duration elapsed,
        Sample.Phase phase,
        List<ProducerSample> producers,
        List<QueueSample> queues,
        boolean blocked) {

    /**
     * What one producer is doing.
     *
     * @param name the producer
     * @param published messages published in this phase
     * @param confirmed messages the broker has confirmed
     * @param failed publishes refused or errored
     * @param publishRate publishes a second since the previous reading
     * @param sendLag how far behind its own schedule it is going out, cumulative
     */
    public record ProducerSample(
            String name,
            long published,
            long confirmed,
            long failed,
            double publishRate,
            LatencySummary sendLag) {
    }

    /**
     * What one queue is doing.
     *
     * @param name the queue
     * @param consumed messages its consumers have handled in this phase
     * @param consumeRate consumes a second since the previous reading
     * @param depth messages waiting, when the broker will say
     * @param endToEnd latency from due to handled, cumulative
     * @param consuming whether anything is reading it at all
     */
    public record QueueSample(
            String name,
            long consumed,
            double consumeRate,
            Long depth,
            LatencySummary endToEnd,
            boolean consuming) {
    }

    /** @return everything published a second, across every producer */
    public double totalPublishRate() {
        return producers.stream().mapToDouble(ProducerSample::publishRate).sum();
    }

    /** @return everything consumed a second, across every queue */
    public double totalConsumeRate() {
        return queues.stream().mapToDouble(QueueSample::consumeRate).sum();
    }

    /** @return every message waiting anywhere, when the depths could be read */
    public long totalDepth() {
        return queues.stream().filter(q -> q.depth() != null).mapToLong(QueueSample::depth).sum();
    }

    @Override
    public String toString() {
        return "ScenarioSample{" + phase + " at " + elapsed.toSeconds() + "s, "
                + Math.round(totalPublishRate()) + "/s out, "
                + Math.round(totalConsumeRate()) + "/s in, " + totalDepth() + " waiting}";
    }
}
