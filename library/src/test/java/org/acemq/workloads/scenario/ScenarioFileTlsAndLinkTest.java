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

import static org.assertj.core.api.Assertions.assertThat;

import org.acemq.amqp.security.Security;
import org.junit.jupiter.api.Test;

/**
 * The two new keys, read from a file rather than built in Java.
 *
 * <p>This is the path that actually matters and the one the model tests do not cover: the reader
 * refuses unknown properties, so a key that is not in the record is a hard failure at parse time
 * no matter how well the Java API works. These would have failed before the record gained them.
 */
class ScenarioFileTlsAndLinkTest {

    @Test
    void aSecurityBlockIsReadFromYaml() {
        String yaml = """
                name: tls
                broker: amqps://localhost:5671
                security:
                  mode: required
                  truststore: /etc/acemq/truststore.p12
                  truststorePassword: secret
                  allowDevelopmentCertificates: true
                exchanges:
                  - { name: ex.orders, type: topic }
                queues:
                  - name: q.orders
                    bindings: [ { exchange: ex.orders, routingKey: "#" } ]
                    consumers: { concurrency: 1 }
                producers:
                  - { name: p, exchange: ex.orders, routingKeys: [ "order.placed" ], rate: 10 }
                """;

        ScenarioFile file = ScenarioReader.parse(yaml, "tls.yaml");

        assertThat(file.security()).isNotNull();
        assertThat(file.security().mode()).isEqualTo("required");
        assertThat(file.security().truststore()).isEqualTo("/etc/acemq/truststore.p12");
        assertThat(file.security().allowDevelopmentCertificates()).isTrue();
        assertThat(file.security().toSecurity().mode()).isEqualTo(Security.Mode.REQUIRED);
    }

    // --dry-run output goes into CI logs, so the one thing that must never appear there is the
    // keystore password.
    @Test
    void describeNamesTheModeAndNeverThePassword() {
        String yaml = """
                name: tls
                broker: amqps://guest:hunter2@localhost:5671
                security:
                  mode: insecure
                  truststorePassword: hunter2
                exchanges:
                  - { name: ex.orders, type: topic }
                queues:
                  - name: q.orders
                    bindings: [ { exchange: ex.orders, routingKey: "#" } ]
                    consumers: { concurrency: 1 }
                producers:
                  - { name: p, exchange: ex.orders, routingKeys: [ "order.placed" ], rate: 10 }
                """;

        String described = ScenarioReader.describe(ScenarioReader.parse(yaml, "tls.yaml"));

        assertThat(described).contains("tls: insecure");
        assertThat(described).doesNotContain("hunter2");
    }

    @Test
    void aQueueOnAnotherBrokerIsReadFromYaml() {
        String yaml = """
                name: federation
                broker: amqp://upstream:5672
                exchanges:
                  - { name: ex.orders, type: topic }
                queues:
                  - name: q.upstream
                    bindings: [ { exchange: ex.orders, routingKey: "#" } ]
                    consumers: { enabled: false }
                  - name: q.downstream
                    broker: amqp://downstream:5672
                    consumers: { concurrency: 2, prefetch: 100 }
                producers:
                  - { name: p, exchange: ex.orders, routingKeys: [ "order.placed" ], rate: 10 }
                """;

        ScenarioFile file = ScenarioReader.parse(yaml, "federation.yaml");
        Scenario scenario = file.toScenario();

        assertThat(scenario.problems()).isEmpty();
        assertThat(scenario.findQueue("q.downstream").orElseThrow().broker())
                .isEqualTo("amqp://downstream:5672");
        assertThat(ScenarioReader.describe(file)).contains("reached across a link");
    }
}
