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

import java.util.EnumSet;
import java.util.Set;

import org.acemq.rabbitmq.admin.RabbitAdmin;

/**
 * What a particular broker will actually give you.
 *
 * <p>Queue types are not a fixed list. Streams arrived in 3.9 and need a feature flag; mirrored
 * classic queues were <strong>removed in 4.0</strong>, and a 4.x broker accepts the policy that
 * used to create them and then ignores it. Offering all four in a designer and letting the broker
 * sort it out produces the worst outcome available: a run that succeeds, reports confidently, and
 * measured a plain classic queue while the label said something else.
 *
 * <p>So the studio asks first:
 *
 * <pre>{@code
 * BrokerCapabilities capabilities = BrokerCapabilities.of(
 *         "http://localhost:15672", "guest", "guest");
 *
 * if (capabilities.supports(QueueType.STREAM)) {
 *     // offer it
 * }
 * }</pre>
 *
 * <p>When the management API is not reachable — it is a separate port and often closed — the
 * answer is {@link #unknown()}, which claims the two types every broker in living memory has and
 * says it is guessing. A guess that is announced is usable; a guess that is not is a bug report
 * six months later.
 */
public final class BrokerCapabilities {

    private final String version;
    private final Set<QueueType> queueTypes;
    private final boolean known;

    private BrokerCapabilities(String version, Set<QueueType> queueTypes, boolean known) {
        this.version = version;
        this.queueTypes = queueTypes;
        this.known = known;
    }

    /**
     * Asks the broker what it is.
     *
     * @param managementUrl the management API, for example {@code http://localhost:15672}
     * @param username a user that may read the overview
     * @param password its password
     * @return what that broker supports, or {@link #unknown()} if it could not be asked
     */
    public static BrokerCapabilities of(String managementUrl, String username, String password) {
        try (RabbitAdmin admin = RabbitAdmin.connect(managementUrl, username, password)) {
            return forVersion(admin.version());
        } catch (RuntimeException e) {
            // A management API that is closed is the normal case in a locked-down environment,
            // not an error worth failing a design session over.
            return unknown();
        }
    }

    /**
     * @param version a broker version such as {@code 4.0.5}
     * @return what a broker of that version supports
     */
    public static BrokerCapabilities forVersion(String version) {
        int major = majorVersion(version);
        EnumSet<QueueType> types = EnumSet.of(QueueType.CLASSIC, QueueType.QUORUM);

        // Streams landed in 3.9. Below that the argument is accepted and the queue is classic.
        if (major > 3 || (major == 3 && minorVersion(version) >= 9)) {
            types.add(QueueType.STREAM);
        }
        // Mirrored classic queues were removed in 4.0. On 4.x the policy still applies cleanly
        // and does nothing at all, which is the failure worth preventing.
        if (major <= 3) {
            types.add(QueueType.CLASSIC_MIRRORED);
        }
        return new BrokerCapabilities(version, types, true);
    }

    /**
     * @return what to assume when the broker cannot be asked: classic and quorum, and honest
     *         about being a guess
     */
    public static BrokerCapabilities unknown() {
        return new BrokerCapabilities(null,
                EnumSet.of(QueueType.CLASSIC, QueueType.QUORUM), false);
    }

    /** @return the broker's version, or null when it could not be asked */
    public String version() {
        return version;
    }

    /** @return whether these capabilities came from a broker rather than from an assumption */
    public boolean isKnown() {
        return known;
    }

    /** @return the queue types this broker will honour */
    public Set<QueueType> queueTypes() {
        return Set.copyOf(queueTypes);
    }

    /**
     * @param type a queue type
     * @return whether this broker will honour it
     */
    public boolean supports(QueueType type) {
        return queueTypes.contains(type);
    }

    /**
     * Why a type is not on offer, in a sentence somebody can act on.
     *
     * @param type the type they wanted
     * @return the reason, or null when it is supported
     */
    public String whyNot(QueueType type) {
        if (supports(type)) {
            return null;
        }
        if (!known) {
            return "this broker could not be asked what it supports, so only classic and quorum"
                    + " queues are offered. Give the studio a management URL to see the rest";
        }
        return switch (type) {
            case CLASSIC_MIRRORED -> "mirrored classic queues were removed in RabbitMQ 4.0 and"
                    + " this broker is " + version + ". Quorum queues replaced them";
            case STREAM -> "streams arrived in RabbitMQ 3.9 and this broker is " + version;
            default -> "this broker is " + version + " and does not offer " + type.label();
        };
    }

    private static int majorVersion(String version) {
        return versionPart(version, 0);
    }

    private static int minorVersion(String version) {
        return versionPart(version, 1);
    }

    private static int versionPart(String version, int index) {
        if (version == null) {
            return 0;
        }
        String[] parts = version.split("[.\\-+]");
        if (parts.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "BrokerCapabilities{" + (version == null ? "unknown broker" : version)
                + ", " + queueTypes + "}";
    }
}
