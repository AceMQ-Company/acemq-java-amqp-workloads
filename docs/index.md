# AceMQ AMQP workloads

A load generator for AMQP brokers. A Java DSL, a command line, an **open-loop**
rate schedule, honest latency percentiles, and a report that says what it
measured rather than what you should do about it.

```bash
java -jar acemq-workload.jar -f workload.yaml --report reports/ --format html,json
```

```yaml
name: orders-peak
broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672

topology:   { exchange: orders, queue: orders.new, routingKey: order.created }
publishers: { threads: 4, rate: 50000, messageSize: 1024 }
consumers:  { concurrency: 8, prefetch: 100, handlerTime: 1ms }

warmup: 10s
runFor: 2m

expect:
  throughputAtLeast: 45000
  p99Below: 50ms
```

Or the same thing from Java:

```java
WorkloadReport report = Workload.named("orders-peak")
        .topology(t -> t.exchange("orders", "topic")
                        .queue("orders.new")
                        .boundTo("orders", "order.created"))
        .publishers(p -> p.threads(4).rate(50_000).messageSize(1024))
        .consumers(c -> c.concurrency(8).prefetch(100)
                         .handlerTime(Duration.ofMillis(1)))
        .warmup(Duration.ofSeconds(10))
        .runFor(Duration.ofMinutes(2))
        .expect(Objective.throughputAtLeast(45_000))
        .expect(Objective.p99Below(Duration.ofMillis(50)))
        .run("amqp://localhost");
```

## The one thing that makes this worth using

**Coordinated omission.** Almost every homemade load tool has this bug, and it
makes the output confidently wrong.

If publishers loop as fast as they can, then when the broker slows down the
offered load drops with it — so latency percentiles come out **better** the worse
the broker behaves. The stall is invisible, because the generator stalled with it
and never offered the load it was supposed to.

This tool publishes on a schedule and measures latency from when a message was
**due**. [How, and why it matters](measurement.md).

## Validity before results

The most valuable checks here are not the ones that judge the broker. They are
the ones that decide whether the run measured the broker *at all*.

```
[INVALID] generator-kept-up
    observed:  publishes ran 6220.2ms behind their own schedule at p99;
               the configured rate was 2,000/s and the achieved rate was 1,048/s
    means:     the configured load was never offered, so this run does not show
               what the broker can take

  result: INVALID — this run did not measure what it was asked to
```

Reporting "300,000/s: FAILED" when the *generator* could only produce 90,000
blames the wrong machine. An invalid run never passes. [The rules](rules.md).

## Diagnosis, not prescription

Every finding carries the measurement that produced it, and none of them tell you
what to change.

A tool that prints "increase prefetch to 250" is guessing — it does not know your
handler's processing time, your message sizes, your network, or what else shares
the broker. And a confident recommendation from a tool gets followed, which makes
a wrong one worse than silence.

## Where to start

- [Getting started](getting-started.md) — build it and run something
- [Tutorials](tutorials.md) — step by step, five of them
- [Command line](cli.md) — every option, with examples
- [Workload file](workload-file.md) — every setting
- [Scenario file](scenario-file.md) — a whole topology, and objectives per node
- [API reference](apidocs/index.html) — the Java surface

## What this is not

- **Not JMeter.** No GUI, no plugin ecosystem, no distributed mode.
- **Not a replacement for `rabbitmq-perf-test`**, which is maintained by the
  RabbitMQ team and battle-tested. What this adds is a DSL that lives in version
  control and runs in CI, topology discovery through the management API, and —
  the real reason it exists — the ability to put **AceMQ's own patterns** under
  load. Retry ladders, outboxes, sagas and pipelines are things perftest cannot
  exercise, because it does not know they exist.
- **Not a capacity number for your production broker.** A container on a laptop
  sharing a CPU with the generator measures the laptop.
