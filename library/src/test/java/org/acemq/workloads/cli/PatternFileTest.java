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
package org.acemq.workloads.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.acemq.amqp.api.RetryPolicy;
import org.acemq.workloads.Workload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the patterns in a workload file")
class PatternFileTest {

    private static Workload parse(String yaml) {
        return WorkloadFile.parse("name: p\nbroker: amqp://localhost\n" + yaml, false, name -> null)
                .workloads().get(0);
    }

    @Test
    @DisplayName("a fixed retry ladder, with its broker threshold")
    void fixedRetry() {
        RetryPolicy policy = parse("""
                consumers:
                  failureRate: 0.2
                  retry: { attempts: 3, delay: 200ms, brokerFrom: 100ms }
                """).consumers().retry();

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.schedule()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(200));
        assertThat(policy.brokerWaitThreshold()).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.brokerRungs()).containsExactly(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("a doubling retry ladder, capped")
    void exponentialRetry() {
        RetryPolicy policy = parse("""
                consumers:
                  retry: { attempts: 5, initialDelay: 100ms, maxDelay: 500ms, jitter: 0 }
                """).consumers().retry();

        assertThat(policy.schedule()).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(400), Duration.ofMillis(500));
        assertThat(policy.jitterFactor()).isZero();
    }

    @Test
    @DisplayName("a retry needs one schedule, and at least two attempts")
    void retryRefusals() {
        assertThatThrownBy(() -> parse("consumers:\n  retry: { attempts: 3 }\n"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("either 'delay'");
        assertThatThrownBy(() -> parse(
                "consumers:\n  retry: { attempts: 3, delay: 1s, initialDelay: 1s, maxDelay: 2s }\n"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> parse("consumers:\n  retry: { attempts: 1, delay: 1s }\n"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("at least 2");
        assertThatThrownBy(() -> parse("consumers:\n  retry: { delay: 1s }\n"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("attempts' is required");
        assertThatThrownBy(() -> parse("consumers:\n  retry: { attempts: 2, delay: 1s, tries: 3 }\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown setting 'consumers.retry.tries'");
    }

    @Test
    @DisplayName("a claim check, in memory by default and in a directory when named")
    void claimCheck() {
        Workload memory = parse("publishers:\n  rate: 10\n  claimCheck: {}\n");
        assertThat(memory.publishers().claimCheckThreshold()).isEqualTo(64 * 1024);
        assertThat(memory.publishers().claimCheckDirectory()).isNull();

        Workload disk = parse("publishers:\n  rate: 10\n  claimCheck: { threshold: 0, directory: /tmp/cc }\n");
        assertThat(disk.publishers().claimCheckThreshold()).isZero();
        assertThat(disk.publishers().claimCheckDirectory()).isEqualTo(Path.of("/tmp/cc"));
        assertThat(disk.publishers().toString()).contains("claim check at 0 bytes in /tmp/cc");
    }

    @Test
    @DisplayName("an outbox, with the relay's batch and poll")
    void outbox() {
        Workload workload = parse("publishers:\n  rate: 500\n  outbox: { batchSize: 50, pollInterval: 20ms }\n");

        assertThat(workload.publishers().outboxBatchSize()).isEqualTo(50);
        assertThat(workload.publishers().outboxPollInterval()).isEqualTo(Duration.ofMillis(20));
        assertThat(parse("publishers:\n  rate: 500\n  outbox: {}\n").publishers().outboxBatchSize())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("an outbox refuses what it cannot measure honestly, as a bad file")
    void outboxRefusals() {
        for (String yaml : List.of(
                "publishers:\n  unthrottled: true\n  outbox: {}\n",
                "publishers:\n  rate: 10\n  confirms: false\n  outbox: {}\n",
                "publishers:\n  rate: 10\n  outbox: {}\n  claimCheck: {}\n",
                "publishers:\n  rate: 10\n  outbox: { pollInterval: 0ms }\n",
                "publishers:\n  rate: 10\n  outbox: { batchSize: 0 }\n")) {
            assertThatThrownBy(() -> parse(yaml)).as(yaml).isInstanceOf(ConfigException.class);
        }
    }

    @Test
    @DisplayName("a dry run shows the pattern, and a ramp over it still parses")
    void dryRun() {
        WorkloadFile file = WorkloadFile.parse("""
                name: p
                broker: amqp://localhost
                publishers: { rate: 100, outbox: { batchSize: 10 } }
                ramp: { step: 100 }
                """, false, name -> null);

        assertThat(file.ramp(0).toString()).isNotBlank();
        assertThat(file.describe()).contains("outbox batch 10");
    }

    @Test
    @DisplayName("the three example files are valid, as written")
    void examples() {
        Workload ladder = WorkloadFile.read(Path.of("../examples/18-retry-ladder.yaml"), name -> null)
                .workloads().get(0);
        assertThat(ladder.consumers().retry().brokerRungs()).containsExactly(Duration.ofMillis(500));

        Workload outbox = WorkloadFile.read(Path.of("../examples/19-outbox.yaml"), name -> null)
                .workloads().get(0);
        assertThat(outbox.publishers().outboxBatchSize()).isEqualTo(100);

        WorkloadFile claims = WorkloadFile.read(Path.of("../examples/20-claim-check.yaml"), name -> null);
        assertThat(claims.workloads()).extracting(w -> w.publishers().claimCheckThreshold())
                .containsExactly(-1, 65536);
    }
}
