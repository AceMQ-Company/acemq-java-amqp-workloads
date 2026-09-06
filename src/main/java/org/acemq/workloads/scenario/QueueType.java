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

/**
 * The kinds of queue a scenario can ask for.
 *
 * <p>Which of these a broker will actually give you depends on its version and its feature flags,
 * which is what {@link BrokerCapabilities} is for. Offering a choice the broker cannot honour is
 * how a load test ends up measuring a classic queue and calling it something else.
 */
public enum QueueType {

    /** The default. Fast, single node, and gone with the node. */
    CLASSIC("classic", "Classic", "One node, no replication. The fastest, and the least safe."),

    /**
     * A classic queue with a mirroring policy over it.
     *
     * <p><strong>Removed in RabbitMQ 4.0.</strong> Against a 4.x broker the policy is ignored and
     * what you measure is a plain classic queue, so this is offered only where it still exists.
     */
    CLASSIC_MIRRORED("classic", "Classic, mirrored",
            "A classic queue with an HA policy. Removed in RabbitMQ 4.0 -- quorum replaced it."),

    /** Replicated by Raft, durable, and slower than classic by design. */
    QUORUM("quorum", "Quorum", "Replicated and durable. The safety costs throughput; measure it."),

    /**
     * An append-only log, read by offset.
     *
     * <p>Not a queue with different settings: consumers do not remove messages, so a slow consumer
     * does not create a backlog and a fast one can re-read. Retention is by size and age rather
     * than by acknowledgement.
     */
    STREAM("stream", "Stream", "An append-only log. Consumers read at their own offset.");

    private final String wireName;
    private final String label;
    private final String description;

    QueueType(String wireName, String label, String description) {
        this.wireName = wireName;
        this.label = label;
        this.description = description;
    }

    /** @return what goes in x-queue-type, which is "classic" for both classic kinds */
    public String wireName() {
        return wireName;
    }

    /** @return a name for a user interface */
    public String label() {
        return label;
    }

    /** @return one sentence on what it costs and what it buys */
    public String description() {
        return description;
    }

    /** @return whether this kind needs a policy applied through the management API */
    public boolean needsPolicy() {
        return this == CLASSIC_MIRRORED;
    }

    /**
     * @param name a name from a file or a form
     * @return the type, matching "classic", "quorum", "stream", "mirrored" and
     *         "classic-mirrored", case-insensitively
     */
    public static QueueType parse(String name) {
        if (name == null || name.isBlank()) {
            return CLASSIC;
        }
        String normalised = name.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return switch (normalised) {
            case "classic" -> CLASSIC;
            case "mirrored", "classic-mirrored", "classic|mirrored" -> CLASSIC_MIRRORED;
            case "quorum" -> QUORUM;
            case "stream" -> STREAM;
            default -> throw new IllegalArgumentException(
                    "unknown queue type '" + name + "'; expected classic, classic-mirrored,"
                            + " quorum or stream");
        };
    }
}
