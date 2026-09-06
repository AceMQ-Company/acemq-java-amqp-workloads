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

import java.util.List;

import org.acemq.workloads.studio.scenario.ScenarioJson;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenarios worth running before you know what you want to run.
 *
 * <p>An empty canvas is the worst first screen a tool can offer. Each of these is a real question
 * somebody arrives with, already drawn, ready to be changed — and each one answers something on
 * its own even if nothing is edited.
 */
@RestController
@RequestMapping("/api/presets")
public class PresetsController {

    /**
     * A starting point.
     *
     * @param id what to call it in a URL
     * @param title what it is called on screen
     * @param question the thing it answers, in one line
     * @param scenario the scenario itself
     */
    public record Preset(String id, String title, String question, ScenarioJson scenario) {
    }

    @GetMapping
    public List<Preset> presets() {
        return List.of(
                new Preset("quorum-vs-classic",
                        "Quorum against classic",
                        "What does the replication actually cost at my rate?",
                        quorumAgainstClassic()),
                new Preset("slow-consumer",
                        "One slow consumer in a fan-out",
                        "One leg of a fan-out is slower than the rest. What happens to the others?",
                        slowConsumer()),
                new Preset("backlog",
                        "A queue nobody is reading",
                        "How fast does a backlog build, and what does the broker do about it?",
                        backlog()),
                new Preset("prefetch",
                        "Prefetch, high and low",
                        "Is prefetch the thing limiting this, or is it the handler?",
                        prefetch()),
                new Preset("dead-letter",
                        "A dead-letter path under load",
                        "Handlers are rejecting some of the traffic. Where does it go, and how fast?",
                        deadLetter()),
                new Preset("find-the-ceiling",
                        "Find the ceiling",
                        "How much can this broker take before it stops keeping up?",
                        findTheCeiling()));
    }

    /**
     * The comparison everybody wants and nobody sets up fairly: the same messages, at the same
     * rate, into both kinds of queue at once. A fanout rather than two runs, so the two queues
     * see the same traffic on the same broker in the same minute.
     */
    private ScenarioJson quorumAgainstClassic() {
        return new ScenarioJson("quorum-vs-classic",
                "The same messages into both kinds of queue at once, so the difference is the"
                        + " queue rather than the minute it ran in.",
                null, null,
                List.of(new ScenarioJson.ExchangeJson("bench", "fanout", null, null, null)),
                List.of(
                        queue("bench.classic", "classic", 4, 200),
                        queue("bench.quorum", "quorum", 4, 200)),
                List.of(producer("load", "bench", "", 10_000, 1024)),
                "10s", "60s", null, null);
    }

    private ScenarioJson slowConsumer() {
        return new ScenarioJson("slow-consumer",
                "Two legs of a fan-out, one with a handler ten times slower. Watch which queue"
                        + " grows and what happens to the other one.",
                null, null,
                List.of(new ScenarioJson.ExchangeJson("orders", "fanout", null, null, null)),
                List.of(
                        new ScenarioJson.QueueJson("orders.fast", "classic", null, null, null,
                                List.of(new ScenarioJson.BindingJson("orders", "")),
                                new ScenarioJson.ConsumersJson(4, 100, "1ms", null, null), null),
                        new ScenarioJson.QueueJson("orders.slow", "classic", null, null, null,
                                List.of(new ScenarioJson.BindingJson("orders", "")),
                                new ScenarioJson.ConsumersJson(1, 100, "10ms", null, null), null)),
                List.of(producer("orders", "orders", "", 2_000, 512)),
                "10s", "60s", null, null);
    }

    private ScenarioJson backlog() {
        return new ScenarioJson("backlog",
                "Nothing is consuming. This is what a stopped service costs per minute, in"
                        + " messages and in memory.",
                null, null,
                List.of(new ScenarioJson.ExchangeJson("events", "topic", null, null, null)),
                List.of(new ScenarioJson.QueueJson("events.parked", "classic", null, null, null,
                        List.of(new ScenarioJson.BindingJson("events", "#")),
                        new ScenarioJson.ConsumersJson(1, 100, null, null, Boolean.FALSE), null)),
                List.of(producer("events", "events", "event.happened", 5_000, 1024)),
                "5s", "60s", null, null);
    }

    private ScenarioJson prefetch() {
        return new ScenarioJson("prefetch",
                "The same handler behind a prefetch of 1 and a prefetch of 500. The gap between"
                        + " the two queues is what prefetch is worth here.",
                null, null,
                List.of(new ScenarioJson.ExchangeJson("work", "fanout", null, null, null)),
                List.of(
                        new ScenarioJson.QueueJson("work.prefetch-1", "classic", null, null, null,
                                List.of(new ScenarioJson.BindingJson("work", "")),
                                new ScenarioJson.ConsumersJson(2, 1, "2ms", null, null), null),
                        new ScenarioJson.QueueJson("work.prefetch-500", "classic", null, null, null,
                                List.of(new ScenarioJson.BindingJson("work", "")),
                                new ScenarioJson.ConsumersJson(2, 500, "2ms", null, null), null)),
                List.of(producer("work", "work", "", 3_000, 256)),
                "10s", "60s", null, null);
    }

    private ScenarioJson deadLetter() {
        return new ScenarioJson("dead-letter",
                "One in twenty messages is rejected by the handler. The dead-letter queue is"
                        + " where they land, and this is how fast it fills.",
                null, null,
                List.of(
                        new ScenarioJson.ExchangeJson("payments", "topic", null, null, null),
                        new ScenarioJson.ExchangeJson("payments.dead", "fanout", null, null, null)),
                List.of(
                        new ScenarioJson.QueueJson("payments.in", "quorum", null, null,
                                "payments.dead",
                                List.of(new ScenarioJson.BindingJson("payments", "#")),
                                new ScenarioJson.ConsumersJson(4, 100, "1ms", 0.05, null), null),
                        new ScenarioJson.QueueJson("payments.parked", "classic", null, null, null,
                                List.of(new ScenarioJson.BindingJson("payments.dead", "")),
                                new ScenarioJson.ConsumersJson(1, 100, null, null, Boolean.FALSE),
                                null)),
                List.of(producer("payments", "payments", "payment.taken", 2_000, 512)),
                "10s", "60s", null, null);
    }

    /**
     * Unthrottled on purpose, and the report will say the latency here means nothing. That is the
     * honest shape of this question: find the ceiling first, then measure latency below it.
     */
    private ScenarioJson findTheCeiling() {
        return new ScenarioJson("find-the-ceiling",
                "As fast as the generator can offer. Read the throughput and ignore the latency:"
                        + " an unthrottled generator stalls when the broker stalls.",
                null, null,
                List.of(new ScenarioJson.ExchangeJson("bench", "topic", null, null, null)),
                List.of(queue("bench.ceiling", "classic", 8, 500)),
                List.of(new ScenarioJson.ProducerJson("flood", "bench", List.of("k"), 0L, null,
                        1024, null, 5_000, null, null)),
                "10s", "60s", null, null);
    }

    private static ScenarioJson.QueueJson queue(String name, String type, int concurrency,
            int prefetch) {
        return new ScenarioJson.QueueJson(name, type, null, null, null,
                List.of(new ScenarioJson.BindingJson("bench", "")),
                new ScenarioJson.ConsumersJson(concurrency, prefetch, null, null, null), null);
    }

    private static ScenarioJson.ProducerJson producer(String name, String exchange, String key,
            long rate, int size) {
        return new ScenarioJson.ProducerJson(name, exchange, List.of(key), rate, null, size,
                null, null, null, null);
    }
}
