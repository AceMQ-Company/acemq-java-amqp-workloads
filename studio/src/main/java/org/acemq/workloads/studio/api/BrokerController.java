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
package org.acemq.workloads.studio.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acemq.rabbitmq.admin.BindingInfo;
import org.acemq.rabbitmq.admin.ExchangeInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.workloads.scenario.BrokerCapabilities;
import org.acemq.workloads.scenario.QueueType;
import org.acemq.workloads.studio.net.BrokerReachability;
import org.acemq.workloads.studio.net.Where;
import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.studio.StudioProperties;
import org.acemq.workloads.studio.tls.TlsProbe;
import org.acemq.workloads.studio.tls.TlsSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Asking a broker where it is, what it supports, and what is already on it. */
@RestController
@RequestMapping("/api/broker")
public class BrokerController {

    private final BrokerReachability reachability;
    private final TlsProbe tls;
    private final StudioProperties properties;

    public BrokerController(BrokerReachability reachability, TlsProbe tls,
            StudioProperties properties) {
        this.reachability = reachability;
        this.tls = tls;
        this.properties = properties;
    }

    /**
     * @param broker an AMQP URL
     * @param management a management URL, when there is one
     * @param username a management user
     * @param password its password
     */
    public record Connection(String broker, String management, String username, String password,
            TlsSettings tls) {

        /** @return the TLS settings, or none when the request did not carry any */
        TlsSettings tlsOrNone() {
            return tls == null ? TlsSettings.none() : tls;
        }
    }

    /**
     * Can this broker be reached from here, and what will it give us.
     *
     * <p>The first half is the one that saves an afternoon. Inside a container, a broker on the
     * user's machine is not at {@code localhost}, and a plain "connection refused" sends people
     * looking at the broker rather than at the network between them.
     *
     * @param connection what to try
     * @return where the studio is, what answered, and what queue types the broker honours
     */
    @PostMapping("/probe")
    public Map<String, Object> probe(@RequestBody Connection connection) {
        Map<String, Object> answer = new LinkedHashMap<>();
        Where where = Where.detect();
        answer.put("where", where.name().toLowerCase(java.util.Locale.ROOT));
        answer.put("whereDescription", where.describe());
        answer.put("hostCandidates", reachability.hostCandidates());

        BrokerReachability.Probe amqp = reachability.probe(connection.broker());
        answer.put("amqp", Map.of(
                "requested", amqp.requestedUrl(),
                "reachable", amqp.isReachable(),
                "url", amqp.reachableUrl() == null ? "" : amqp.reachableUrl(),
                "rewritten", amqp.wasRewritten(),
                "explanation", amqp.explain(),
                "attempts", amqp.attempts()));

        // A TCP connect to 5671 proves nothing about TLS: the port answers whether or not the
        // certificate on it is one this client will accept. So when the URL says amqps, the
        // handshake is actually made and what it found is reported.
        TlsSettings settings = connection.tlsOrNone();
        boolean wantsTls = settings.enabled()
                || String.valueOf(connection.broker()).startsWith("amqps://");
        if (wantsTls && amqp.isReachable()) {
            TlsSettings effective = settings.enabled() ? settings
                    : new TlsSettings(true, null, null, null, null, null, null, false, false);
            answer.put("tls", tls.probe(amqp.reachableUrl(), effective,
                    properties.tlsWorkingDirectory()));
        }

        if (connection.management() != null && !connection.management().isBlank()) {
            BrokerReachability.Probe http = reachability.probe(connection.management());
            answer.put("management", Map.of(
                    "requested", http.requestedUrl(),
                    "reachable", http.isReachable(),
                    "url", http.reachableUrl() == null ? "" : http.reachableUrl(),
                    "rewritten", http.wasRewritten(),
                    "explanation", http.explain()));

            BrokerCapabilities capabilities = http.isReachable()
                    ? BrokerCapabilities.of(http.reachableUrl(), connection.username(),
                            connection.password())
                    : BrokerCapabilities.unknown();
            answer.put("capabilities", describe(capabilities));
        } else {
            // Without the management API the queue types cannot be established, and guessing
            // silently is how somebody ends up measuring a classic queue labelled "mirrored".
            answer.put("capabilities", describe(BrokerCapabilities.unknown()));
        }

        return answer;
    }

    private Map<String, Object> describe(BrokerCapabilities capabilities) {
        List<Map<String, Object>> types = new ArrayList<>();
        for (QueueType type : QueueType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            entry.put("label", type.label());
            entry.put("description", type.description());
            entry.put("supported", capabilities.supports(type));
            entry.put("whyNot", capabilities.whyNot(type));
            types.add(entry);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("version", capabilities.version());
        answer.put("known", capabilities.isKnown());
        answer.put("queueTypes", types);
        return answer;
    }

    /**
     * Reads a topology off a live broker and turns it into a scenario to edit.
     *
     * <p>The fastest way to a useful scenario is not drawing one: it is taking the topology that
     * already exists, switching most of it off, and putting load on the part in question. Nobody
     * types out forty queues to reproduce a Monday morning.
     *
     * <p>Consumers are left switched off deliberately. An imported topology is somebody else's
     * production shape, and starting consumers on every queue of it because a form defaulted to
     * one is not a decision a tool should make.
     *
     * @param connection the broker to read
     * @return a scenario, ready to edit
     */
    @PostMapping("/import")
    public ResponseEntity<?> importTopology(@RequestBody Connection connection) {
        if (connection.management() == null || connection.management().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "importing a topology needs the management API, which is a separate port"
                            + " (15672 by default). AMQP cannot enumerate what is on a broker"));
        }

        BrokerReachability.Probe http = reachability.probe(connection.management());
        if (!http.isReachable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "the management API could not be reached",
                    "explanation", http.explain()));
        }

        try (RabbitAdmin admin = RabbitAdmin.connect(http.reachableUrl(), connection.username(),
                connection.password())) {

            List<ScenarioFile.ExchangeJson> exchanges = new ArrayList<>();
            for (ExchangeInfo exchange : admin.exchanges()) {
                // The default exchange and the amq.* set are the broker's, not the user's, and a
                // designer full of them is a designer nobody can read.
                if (exchange.name().isBlank() || exchange.name().startsWith("amq.")) {
                    continue;
                }
                exchanges.add(new ScenarioFile.ExchangeJson(exchange.name(), exchange.type(),
                        exchange.durable() ? null : Boolean.FALSE, null, null));
            }

            List<ScenarioFile.QueueJson> queues = new ArrayList<>();
            for (QueueInfo queue : admin.queues()) {
                List<ScenarioFile.BindingJson> bindings = new ArrayList<>();
                for (BindingInfo binding : admin.bindingsForQueue(queue.name())) {
                    if (binding.source() != null && !binding.source().isBlank()) {
                        bindings.add(new ScenarioFile.BindingJson(
                                binding.source(), binding.routingKey()));
                    }
                }
                queues.add(new ScenarioFile.QueueJson(
                        queue.name(),
                        queue.type() == null ? "classic" : queue.type(),
                        queue.durable() ? null : Boolean.FALSE,
                        null,
                        null,
                        bindings.isEmpty() ? null : bindings,
                        new ScenarioFile.ConsumersJson(1, 100, null, null, Boolean.FALSE),
                        null));
            }

            ScenarioFile scenario = new ScenarioFile(
                    "imported", "read from " + BrokerReachability.hostOf(http.reachableUrl()),
                    connection.broker(), connection.management(),
                    exchanges, queues, List.of(), "5s", "30s",
                    // Never declare an imported topology: it exists, and redeclaring it with
                    // arguments this read did not capture is how a load test changes production.
                    Boolean.FALSE, null);

            return ResponseEntity.ok(scenario);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "the topology could not be read: " + e.getMessage()));
        }
    }
}
