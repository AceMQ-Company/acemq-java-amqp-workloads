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
package org.acemq.workloads.studio.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.acemq.workloads.Workload;
import org.acemq.workloads.cli.WorkloadFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the load designer's workload file")
class WorkloadYamlTest {

    private static final Map<String, String> ENV = Map.of();

    @Test
    @DisplayName("every field the designer sets comes back from the library's parser")
    void roundTrips() {
        WorkloadYaml.Form form = new WorkloadYaml.Form("orders-load", "amqp://u:p@broker:5781/",
                "orders", "direct", "orders.q", "orders.key", "quorum", false,
                3, 750L, false, 2048, true, false, 500, 90_000L,
                6, 250, "2ms", 0.01,
                "5s", "2m",
                700L, "3ms", "25ms", "100ms", true);

        String yaml = WorkloadYaml.toYaml(form);
        WorkloadFile file = WorkloadFile.parseYaml(yaml, ENV::get);
        Workload w = file.workloads().get(0);

        assertThat(file.size()).isOne();
        assertThat(file.brokerUrl(0)).isEqualTo("amqp://u:p@broker:5781/");
        assertThat(w.name()).isEqualTo("orders-load");
        assertThat(w.topology().exchange()).isEqualTo("orders");
        assertThat(w.topology().exchangeType()).isEqualTo("direct");
        assertThat(w.topology().queue()).isEqualTo("orders.q");
        assertThat(w.topology().routingKey()).isEqualTo("orders.key");
        assertThat(w.topology().queueType()).isEqualTo("quorum");
        assertThat(w.topology().shouldDeclare()).isFalse();
        assertThat(w.publishers().threadCount()).isEqualTo(3);
        assertThat(w.publishers().rate()).isEqualTo(750);
        assertThat(w.publishers().payload().size()).isEqualTo(2048);
        assertThat(w.publishers().payload().isRandom()).isTrue();
        assertThat(w.publishers().confirms()).isFalse();
        assertThat(w.publishers().maxInFlight()).isEqualTo(500);
        assertThat(w.publishers().maxMessages()).isEqualTo(90_000);
        assertThat(w.consumers().concurrency()).isEqualTo(6);
        assertThat(w.consumers().prefetch()).isEqualTo(250);
        assertThat(w.consumers().handlerTime()).isEqualTo(Duration.ofMillis(2));
        assertThat(w.consumers().failureRate()).isEqualTo(0.01);
        assertThat(w.warmup()).isEqualTo(Duration.ofSeconds(5));
        assertThat(w.duration()).isEqualTo(Duration.ofMinutes(2));
        // Seven default rules and the five objectives asked for.
        assertThat(w.rules()).hasSize(7 + 5);
    }

    @Test
    @DisplayName("the defaults are a file the library accepts, and an unset field is left out")
    void defaultsParse() {
        String yaml = WorkloadYaml.toYaml(WorkloadYaml.defaults());

        assertThat(WorkloadYaml.check(yaml, ENV::get).valid()).isTrue();
        assertThat(yaml).doesNotContain("expect:").doesNotContain("exchange:")
                .contains("${DRILL_BROKER:-amqp://guest:guest@localhost:5772}");
    }

    @Test
    @DisplayName("unthrottled and until-stopped are written the way the parser reads them")
    void unthrottledUntilStopped() {
        WorkloadYaml.Form d = WorkloadYaml.defaults();
        WorkloadYaml.Form form = new WorkloadYaml.Form(d.name(), d.broker(), null, null, "q",
                null, "classic", true, 1, 500L, true, 1024, false, true, null, null, 1, 10,
                null, null, "0s", "until-stopped", null, null, null, null, false);

        Workload w = WorkloadFile.parseYaml(WorkloadYaml.toYaml(form), ENV::get).workloads().get(0);

        assertThat(w.publishers().isUnthrottled()).isTrue();
        assertThat(w.runsUntilStopped()).isTrue();
    }

    @Test
    @DisplayName("a file the library refuses comes back invalid, in the library's own words")
    void refusesWhatTheLibraryRefuses() {
        WorkloadYaml.Check check = WorkloadYaml.check("name: x\npublishers:\n  rat: 10\n", ENV::get);

        assertThat(check.valid()).isFalse();
        assertThat(check.problems()).singleElement().asString()
                .contains("unknown setting 'publishers.rat'");
        assertThat(WorkloadYaml.check("", ENV::get).valid()).isFalse();
        assertThat(WorkloadYaml.check("name: x\nbroker: ${NOT_SET_ANYWHERE}\n", ENV::get).problems())
                .singleElement().asString().contains("NOT_SET_ANYWHERE");
    }
}
