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
import java.util.List;

import org.acemq.workloads.scenario.ScenarioFile;
import org.acemq.workloads.scenario.ScenarioFile.BindingJson;
import org.acemq.workloads.scenario.ScenarioFile.ConsumersJson;
import org.acemq.workloads.scenario.ScenarioFile.ExchangeJson;
import org.acemq.workloads.scenario.ScenarioFile.ProducerJson;
import org.acemq.workloads.scenario.ScenarioFile.QueueJson;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenarios worth running before you know what you want to run.
 *
 * <p>An empty canvas is the worst first screen a tool can offer. Each of these is a real question
 * somebody arrives with, already drawn, ready to be changed — and each one answers something on
 * its own even if nothing is edited.
 *
 * <p>They come in two kinds. The <strong>measurements</strong> isolate one variable: a queue type,
 * a prefetch, a slow consumer. The <strong>shapes</strong> are whole topologies with the exchange
 * types wired the way a real system wires them, and they answer a different question — not "how
 * fast is this queue" but "does the routing do what I think, and what does the whole thing cost
 * when every path is busy at once".
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
    public record Preset(String id, String title, String question, ScenarioFile scenario) {
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
                        findTheCeiling()),
                new Preset("routing-shapes",
                        "Every routing rule at once",
                        "Direct, topic and fanout on one broker: does each one deliver what it "
                                + "should, and what do eleven queues cost together?",
                        routingShapes()),
                new Preset("ecommerce",
                        "Event-driven commerce",
                        "Orders broadcast to everyone, payments routed by exact type, shipping by "
                                + "pattern — the shape most order systems actually have.",
                        eventDrivenCommerce()),
                new Preset("market-data",
                        "A trading venue",
                        "Market data fanned out to every desk while orders route by instrument: "
                                + "small messages, high rate, and one slow subscriber.",
                        tradingVenue()),
                new Preset("fan-in",
                        "Many writers, one queue",
                        "Eight services publishing into the same queue. Where does the contention "
                                + "show up — the exchange, the queue, or the consumers?",
                        fanIn()));
    }

    // ---- the measurements -------------------------------------------------

    /**
     * The comparison everybody wants and nobody sets up fairly: the same messages, at the same
     * rate, into both kinds of queue at once. A fanout rather than two runs, so the two queues see
     * the same traffic on the same broker in the same minute.
     */
    private ScenarioFile quorumAgainstClassic() {
        return scenario("quorum-vs-classic",
                "The same messages into both kinds of queue at once, so the difference is the"
                        + " queue rather than the minute it ran in.",
                List.of(exchange("bench", "fanout")),
                List.of(
                        queue("bench.classic", "classic", consumers(4, 200), bind("bench", "")),
                        queue("bench.quorum", "quorum", consumers(4, 200), bind("bench", ""))),
                List.of(producer("load", "bench", 10_000, 1024, "")),
                "10s", "60s");
    }

    private ScenarioFile slowConsumer() {
        return scenario("slow-consumer",
                "Two legs of a fan-out, one with a handler ten times slower. Watch which queue"
                        + " grows and what happens to the other one.",
                List.of(exchange("orders", "fanout")),
                List.of(
                        queue("orders.fast", "classic",
                                new ConsumersJson(4, 100, "1ms", null, null), bind("orders", "")),
                        queue("orders.slow", "classic",
                                new ConsumersJson(1, 100, "10ms", null, null), bind("orders", ""))),
                List.of(producer("orders", "orders", 2_000, 512, "")),
                "10s", "60s");
    }

    private ScenarioFile backlog() {
        return scenario("backlog",
                "Nothing is consuming. This is what a stopped service costs per minute, in"
                        + " messages and in memory.",
                List.of(exchange("events", "topic")),
                List.of(queue("events.parked", "classic",
                        new ConsumersJson(1, 100, null, null, Boolean.FALSE),
                        bind("events", "#"))),
                List.of(producer("events", "events", 5_000, 1024, "event.happened")),
                "5s", "60s");
    }

    private ScenarioFile prefetch() {
        return scenario("prefetch",
                "The same handler behind a prefetch of 1 and a prefetch of 500. The gap between"
                        + " the two queues is what prefetch is worth here.",
                List.of(exchange("work", "fanout")),
                List.of(
                        queue("work.prefetch-1", "classic",
                                new ConsumersJson(2, 1, "2ms", null, null), bind("work", "")),
                        queue("work.prefetch-500", "classic",
                                new ConsumersJson(2, 500, "2ms", null, null), bind("work", ""))),
                List.of(producer("work", "work", 3_000, 256, "")),
                "10s", "60s");
    }

    private ScenarioFile deadLetter() {
        return scenario("dead-letter",
                "One in twenty messages is rejected by the handler. The dead-letter queue is"
                        + " where they land, and this is how fast it fills.",
                List.of(exchange("payments", "topic"), exchange("payments.dead", "fanout")),
                List.of(
                        deadLettering("payments.in", "quorum",
                                new ConsumersJson(4, 100, "1ms", 0.05, null), "payments.dead",
                                bind("payments", "#")),
                        queue("payments.parked", "classic",
                                new ConsumersJson(1, 100, null, null, Boolean.FALSE),
                                bind("payments.dead", ""))),
                List.of(producer("payments", "payments", 2_000, 512, "payment.taken")),
                "10s", "60s");
    }

    /**
     * Unthrottled on purpose, and the report will say the latency here means nothing. That is the
     * honest shape of this question: find the ceiling first, then measure latency below it.
     */
    private ScenarioFile findTheCeiling() {
        return scenario("find-the-ceiling",
                "As fast as the generator can offer. Read the throughput and ignore the latency:"
                        + " an unthrottled generator stalls when the broker stalls.",
                List.of(exchange("bench", "topic")),
                List.of(queue("bench.ceiling", "classic", consumers(8, 500), bind("bench", "#"))),
                List.of(new ProducerJson("flood", "bench", List.of("k"), 0L, null, 1024, null,
                        5_000, null, null)),
                "10s", "60s");
    }

    // ---- the shapes -------------------------------------------------------

    /**
     * All three exchange types at once, wired the way each is meant to be used.
     *
     * <p>The point is routing rather than throughput: a direct exchange delivering to exactly one
     * queue per key, a topic exchange where {@code *} takes one word and {@code #} takes any
     * number, and a fanout that ignores keys entirely. Run it and the per-queue counts say whether
     * the patterns match what you believed — {@code orders.*.eu} takes
     * {@code orders.created.eu} and not {@code orders.created.paid.eu}, and the queue bound to
     * {@code #} receives everything, including the key with no dots in it.
     */
    private ScenarioFile routingShapes() {
        List<ExchangeJson> exchanges = List.of(
                exchange("shapes.direct", "direct"),
                exchange("shapes.topic", "topic"),
                exchange("shapes.fanout", "fanout"));

        List<QueueJson> queues = new ArrayList<>(List.of(
                // Direct: one queue per exact key, and nothing else reaches them.
                queue("direct.new", "classic", consumers(2, 100), bind("shapes.direct", "new")),
                queue("direct.amend", "classic", consumers(2, 100), bind("shapes.direct", "amend")),
                queue("direct.cancel", "classic", consumers(2, 100),
                        bind("shapes.direct", "cancel")),

                // Topic: the four patterns worth knowing, on one exchange.
                queue("topic.all-orders", "classic", consumers(2, 100),
                        bind("shapes.topic", "orders.#")),
                queue("topic.eu-orders", "classic", consumers(2, 100),
                        bind("shapes.topic", "orders.*.eu")),
                queue("topic.any-filled", "classic", consumers(2, 100),
                        bind("shapes.topic", "*.filled")),
                queue("topic.everything", "classic", consumers(2, 100),
                        bind("shapes.topic", "#")),

                // Fanout: every queue gets a copy, whatever the key says.
                queue("fanout.audit", "classic", consumers(2, 100), bind("shapes.fanout", "")),
                queue("fanout.search", "classic", consumers(2, 100), bind("shapes.fanout", "")),
                queue("fanout.analytics", "classic", consumers(2, 100), bind("shapes.fanout", "")),
                queue("fanout.archive", "classic", consumers(2, 100), bind("shapes.fanout", ""))));

        List<ProducerJson> producers = List.of(
                // The keys are used in turn, so every binding gets traffic rather than the first.
                producer("direct-keys", "shapes.direct", 3_000, 512, "new", "amend", "cancel"),
                producer("topic-keys", "shapes.topic", 3_000, 512,
                        "orders.created.eu", "orders.created.us", "orders.created.paid.eu",
                        "trade.filled", "orders", "shipments.dispatched.eu"),
                producer("fanout-copies", "shapes.fanout", 1_000, 512, "ignored"));

        return scenario("routing-shapes",
                "Direct, topic and fanout side by side. The per-queue counts are the answer:"
                        + " orders.*.eu takes orders.created.eu and not orders.created.paid.eu,"
                        + " and the queue bound to # gets everything including the bare key.",
                exchanges, queues, producers, "10s", "60s");
    }

    /**
     * The shape most order systems actually have.
     *
     * <p>An order is broadcast to everyone who cares, a payment is routed by exactly what it is,
     * and shipping subscribes by pattern because it wants a region rather than an event. Three
     * exchange types, nine queues, and consumer counts that differ per queue the way they differ
     * in a real system — the analytics leg is deliberately thinner than the rest.
     */
    private ScenarioFile eventDrivenCommerce() {
        List<ExchangeJson> exchanges = List.of(
                exchange("orders.broadcast", "fanout"),
                exchange("payments.direct", "direct"),
                exchange("shipping.events", "topic"));

        List<QueueJson> queues = List.of(
                // Everyone hears about an order.
                queue("orders.fulfilment", "quorum", consumers(4, 200),
                        bind("orders.broadcast", "")),
                queue("orders.analytics", "classic",
                        // Thinner on purpose: analytics is where the backlog shows up first.
                        new ConsumersJson(1, 50, "3ms", null, null), bind("orders.broadcast", "")),

                // A payment goes to exactly one place, by what it is.
                queue("payments.authorise", "quorum",
                        new ConsumersJson(4, 100, "2ms", null, null),
                        bind("payments.direct", "authorise")),
                queue("payments.capture", "quorum",
                        new ConsumersJson(2, 100, "2ms", null, null),
                        bind("payments.direct", "capture")),
                queue("payments.refund", "quorum",
                        new ConsumersJson(1, 50, "5ms", null, null),
                        bind("payments.direct", "refund")),

                // Shipping subscribes to patterns: a region, a carrier, everything.
                queue("shipping.eu", "classic", consumers(3, 150),
                        bind("shipping.events", "shipment.*.eu")),
                queue("shipping.us", "classic", consumers(3, 150),
                        bind("shipping.events", "shipment.*.us")),
                queue("shipping.exceptions", "classic",
                        new ConsumersJson(2, 100, "4ms", null, null),
                        bind("shipping.events", "shipment.exception.#")),
                queue("shipping.audit", "classic", consumers(1, 200),
                        bind("shipping.events", "#")));

        List<ProducerJson> producers = List.of(
                producer("checkout", "orders.broadcast", 4_000, 768, "order.placed"),
                producer("payments", "payments.direct", 2_500, 512,
                        "authorise", "authorise", "capture", "refund"),
                producer("warehouse", "shipping.events", 3_000, 640,
                        "shipment.dispatched.eu", "shipment.dispatched.us",
                        "shipment.delivered.eu", "shipment.exception.customs.eu"));

        return scenario("ecommerce",
                "Orders broadcast to fulfilment and analytics, payments routed by exact type,"
                        + " shipping subscribed by region and by exception. Nine queues, three"
                        + " exchange types, and one deliberately thin consumer.",
                exchanges, queues, producers, "10s", "90s");
    }

    /**
     * A trading venue: small messages, a high rate, and a subscriber that cannot keep up.
     *
     * <p>Market data is the workload that breaks the assumptions built up on order traffic. The
     * messages are tiny, the rate is an order of magnitude higher, every desk wants a copy, and
     * one slow subscriber must not be allowed to hold up the others — which is exactly what this
     * scenario shows, queue by queue.
     */
    private ScenarioFile tradingVenue() {
        List<ExchangeJson> exchanges = List.of(
                exchange("market.data", "fanout"),
                exchange("market.instruments", "topic"),
                exchange("orders.route", "direct"));

        List<QueueJson> queues = List.of(
                // Every desk gets every tick.
                queue("desk.equities", "classic", consumers(4, 500), bind("market.data", "")),
                queue("desk.derivatives", "classic", consumers(4, 500), bind("market.data", "")),
                queue("desk.risk", "classic",
                        // The one that cannot keep up. Nothing else should notice.
                        new ConsumersJson(1, 100, "2ms", null, null), bind("market.data", "")),
                queue("market.recorder", "stream", consumers(2, 500), bind("market.data", "")),

                // Instrument-level subscriptions, by pattern.
                queue("quotes.nasdaq", "classic", consumers(3, 300),
                        bind("market.instruments", "quote.*.nasdaq")),
                queue("quotes.lse", "classic", consumers(3, 300),
                        bind("market.instruments", "quote.*.lse")),
                queue("trades.all", "classic", consumers(3, 300),
                        bind("market.instruments", "trade.#")),
                queue("instruments.tape", "classic", consumers(1, 500),
                        bind("market.instruments", "#")),

                // Orders go to exactly one desk each.
                queue("orders.new", "quorum", consumers(4, 200), bind("orders.route", "new")),
                queue("orders.cancel", "quorum", consumers(2, 200),
                        bind("orders.route", "cancel")),
                queue("orders.amend", "quorum", consumers(2, 200), bind("orders.route", "amend")));

        List<ProducerJson> producers = List.of(
                // Ticks are small and frequent; this is the shape that finds the ceiling.
                producer("feed", "market.data", 20_000, 128, "tick"),
                producer("instruments", "market.instruments", 8_000, 160,
                        "quote.aapl.nasdaq", "quote.vod.lse", "trade.aapl.nasdaq",
                        "trade.vod.lse", "quote.msft.nasdaq"),
                producer("order-entry", "orders.route", 4_000, 256, "new", "new", "amend", "cancel"));

        return scenario("market-data",
                "A market data feed fanned out to four consumers including one that is too slow,"
                        + " instrument subscriptions by pattern, and order entry routed by type."
                        + " Small messages, high rate: the workload that breaks assumptions built"
                        + " on order traffic.",
                exchanges, queues, producers, "15s", "90s");
    }

    /**
     * Eight producers into one queue.
     *
     * <p>The other direction from a fan-out, and a different question: a single queue is a single
     * Erlang process on one node, so this is where contention appears rather than in the
     * exchange. Worth running against a classic and a quorum queue in turn — the difference is
     * usually larger here than on a fan-out.
     */
    private ScenarioFile fanIn() {
        List<ProducerJson> producers = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            producers.add(producer("service-" + i, "ingest", 2_000, 512, "event"));
        }

        return scenario("fan-in",
                "Eight services publishing into one queue at 2,000/s each. A queue is one process"
                        + " on one node, so this is where contention shows up -- and it is worth"
                        + " running again with the queue set to quorum.",
                List.of(exchange("ingest", "direct")),
                List.of(queue("ingest.all", "classic", consumers(8, 300), bind("ingest", "event"))),
                producers, "10s", "60s");
    }

    // ---- the small pieces -------------------------------------------------

    private static ScenarioFile scenario(String name, String description,
            List<ExchangeJson> exchanges, List<QueueJson> queues, List<ProducerJson> producers,
            String warmup, String runFor) {
        return new ScenarioFile(name, description, null, null, exchanges, queues, producers,
                warmup, runFor, null, null);
    }

    private static ExchangeJson exchange(String name, String type) {
        return new ExchangeJson(name, type, null, null, null);
    }

    private static QueueJson queue(String name, String type, ConsumersJson consumers,
            BindingJson... bindings) {
        return new QueueJson(name, type, null, null, null, List.of(bindings), consumers, null);
    }

    private static QueueJson deadLettering(String name, String type, ConsumersJson consumers,
            String deadLetterExchange, BindingJson... bindings) {
        return new QueueJson(name, type, null, null, deadLetterExchange, List.of(bindings),
                consumers, null);
    }

    private static BindingJson bind(String exchange, String routingKey) {
        return new BindingJson(exchange, routingKey);
    }

    private static ConsumersJson consumers(int concurrency, int prefetch) {
        return new ConsumersJson(concurrency, prefetch, null, null, null);
    }

    private static ProducerJson producer(String name, String exchange, long rate, int size,
            String... routingKeys) {
        return new ProducerJson(name, exchange, List.of(routingKeys), rate, null, size, null, null,
                null, null);
    }
}
