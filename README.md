# acemq-java-amqp-workloads

[![ci](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/actions/workflows/ci.yml)
[![authorship guard](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/actions/workflows/attribution-guard.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/actions/workflows/attribution-guard.yml)
[![version](https://img.shields.io/badge/version-0.1.0-blue)](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/releases)
[![artifacts](https://img.shields.io/badge/artifacts-acemq.org%2Fmaven-blue)](https://acemq.org/maven/)
[![docs](https://img.shields.io/badge/docs-acemq.org-blue)](https://acemq.org/acemq-java-amqp-workloads/)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](#requirements)
[![brokers](https://img.shields.io/badge/broker-RabbitMQ-lightgrey)](#requirements)

A load generator for AMQP brokers: a Java DSL, an **open-loop** rate schedule,
honest latency percentiles, and a report that says what it measured rather than
what you should do about it.

> **Status: `0.1.0`, published.** 39 unit tests and 5 integration tests against a
> real broker. The library is on the
> [Maven repository](https://acemq-company.github.io/maven/); the CLI jar is
> attached to the [release](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/releases/tag/v0.1.0).

```java
WorkloadReport report = Workload.named("orders-peak")
        .topology(t -> t
                .exchange("orders", "topic")
                .queue("orders.new")
                .boundTo("orders", "order.created"))
        .publishers(p -> p
                .threads(4)
                .rate(50_000)
                .messageSize(1024))
        .consumers(c -> c
                .concurrency(8)
                .prefetch(100)
                .handlerTime(Duration.ofMillis(1)))
        .warmup(Duration.ofSeconds(10))
        .runFor(Duration.ofMinutes(2))
        .expect(Objective.throughputAtLeast(45_000))
        .expect(Objective.p99Below(Duration.ofMillis(50)))
        .run("amqp://localhost");

System.out.println(report.format());
if (!report.passed()) {
    System.exit(1);
}
```

## From the command line

```bash
java -jar acemq-workload.jar -f workload.yaml --report reports/ --format html,md,json
```

```yaml
# Peak hour is 38,000/s; 50,000 gives headroom for a bad Monday.
broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
management: http://localhost:15672

publishers: { threads: 4, rate: 50000, messageSize: 1024 }
consumers:  { concurrency: 8, prefetch: 100, handlerTime: 1ms }
warmup: 10s
runFor: 2m

workloads:
  - name: classic-queue
    topology: { exchange: bench, queue: bench.classic, routingKey: k, queueType: classic }
    expect: { throughputAtLeast: 45000, p99Below: 50ms }
  - name: quorum-queue
    topology: { exchange: bench, queue: bench.quorum, routingKey: k, queueType: quorum }
    expect: { throughputAtLeast: 45000, p99Below: 50ms }
```

One file, two runs, compared side by side. `${VAR}` comes from the environment,
so a password never has to live in a file that gets committed, and `--dry-run`
prints the resolved configuration with it redacted.

### Exit codes are the interface

More important than the report format: a pipeline reads the exit code, a person
reads the report. These three are genuinely different problems and a build that
treats them alike will retry the one that can never succeed.

| | |
|---|---|
| `0` | passed |
| `1` | a **sound** run missed an objective — the broker's answer is "no" |
| `2` | a run was **invalid**; nothing was measured, and retrying as-is gives the same non-answer |
| `3` | the workload file is wrong |
| `4` | the broker could not be reached |

### Formats

`html`, `md`, `json` — default `html,json`.

**JSON is the one to care about.** It is what a pipeline reads to decide whether
to promote a build, what a dashboard graphs over time, and what lets two runs be
compared without re-running either. HTML is for people; JSON is for everything
else, and the HTML embeds it so a report mailed to somebody is still
re-analysable rather than a picture of numbers.

**PDF is deliberately absent.** It needs a layout engine and its fonts — a large
dependency in a tool whose output is a table and a list — and the result looks
worse than what a browser prints. The HTML carries `@media print` rules, so
printing it to PDF produces a better document with nothing added to the build.
Passing `--format pdf` says so rather than failing quietly.

## A whole topology, not one path

A workload file describes one path. A **scenario** describes the shape a system
actually has — several exchanges, several queues with their own bindings and
their own consumer counts, several producers — and runs all of it at once. It is
what the [studio](studio/README.md) draws and exports, and the same `-f` reads
it; which kind of file it is, is decided by what is in it.

```yaml
name: orders-peak
exchanges:
  - { name: orders, type: topic }
queues:
  - name: orders.shipping
    type: quorum
    bindings: [ { exchange: orders, routingKey: "order.*" } ]
    consumers: { concurrency: 8, prefetch: 200 }
    expect: { p99Below: 50ms, noBacklog: true }
  - name: orders.audit
    type: stream
    bindings: [ { exchange: orders, routingKey: "#" } ]
    consumers: { concurrency: 1 }
producers:
  - name: checkout
    exchange: orders
    routingKeys: [ order.placed, order.cancelled ]
    rate: 20000
    expect: { withinPercentOfOffered: 5, noFailures: true }
runFor: 2m
```

**`expect` is per node, and that is the point.** The interesting property is
usually asymmetric — here the audit stream may lag as much as it likes while the
fulfilment queue must not, and a single overall p99 would average away exactly
that distinction. What is not stated is not checked; anything stated produces a
`FAILED` finding naming the node and both numbers, and exit `1`.

[Every field](docs/scenario-file.md).

## Why a separate repository

It needs both halves of AceMQ and neither may depend on it:

```
acemq-java-amqp-workloads
   ├── acemq-java-amqp              publish / consume
   └── acemq-java-rabbitmq-admin    topology, definitions, per-queue depth
```

`acemq-java-amqp` must never acquire a dependency on the management API — that
rule is why the admin library is separate in the first place. So a workloads
module inside it would either break the rule or be crippled. It has its own
version line, starting at `0.1.0`, and is **not** a module of the messaging
library.

## The one thing that makes this worth using

**Coordinated omission.** Almost every homemade load tool has this bug, and it
makes the output confidently wrong.

If publishers loop as fast as they can, then when the broker slows down the
offered load drops with it — so the latency percentiles come out *better* the
worse the broker behaves. A stall becomes invisible, because the generator was
stalled with it and never offered the load it was supposed to.

This library publishes on a **schedule**: message *n* is due at
`start + n/rate`, computed from the start rather than from the previous send.
Latency is measured from when a message was **due**, not from when it actually
went out. A broker that stalls for 100ms produces latencies of 100ms, 99ms,
98ms — which is what a client on that schedule actually experienced.

The gap between due and sent is reported as **send lag**, and it is the number
that decides whether the run measured anything at all.

## Validity before results

The most valuable rules here are not the ones that judge the broker. They are
the ones that decide whether the run measured the broker *at all*.

A load test that quietly failed to offer its load still produces a full set of
confident numbers. Those numbers describe the client. So:

```
[INVALID] generator-kept-up
    observed:  publishes ran 6220.2ms behind their own schedule at p99;
               the configured rate was 2,000/s and the achieved rate was 1,048/s
    means:     the configured load was never offered, so this run does not show
               what the broker can take. Raise publisher threads, or run the
               generator on a machine that is not also the broker, and repeat

  result: INVALID — this run did not measure what it was asked to
```

`Severity.INVALID` outranks `FAILED` deliberately. Reporting "300,000/s: FAILED"
when the *generator* could only produce 90,000 blames the wrong machine.
An invalid run never passes, whatever its objectives say.

## Diagnosis, not prescription

Every finding carries the measurement that produced it, and none of them tell
you what to change.

A tool that prints "increase prefetch to 250" is guessing. It does not know your
handler's processing time distribution, your message sizes, your network, your
disk, or what else shares the broker — and a confident recommendation from a
tool gets followed, which makes a wrong one worse than silence.

What a tool can say honestly is what it measured and what that rules in or out.
So a `Finding` has an `observation()` and an `implication()`, and neither is
advice.

## The rules that always run

| Rule | Severity | Fires when |
|---|---|---|
| `generator-kept-up` | **INVALID** | Publishes ran >100ms behind schedule at p99 |
| `broker-not-blocked` | **INVALID** | The connection was blocked by a resource alarm |
| `publishes-succeeded` | FAILED | Any publish was refused or errored |
| `consumers-kept-up` | WARNING | The queue grew for the whole run |
| `confirms-were-on` | WARNING | Confirms were off, so the rate is an upper bound |
| `tail-is-not-extreme` | WARNING | p99 is more than 10× the median |
| `run-was-long-enough` | WARNING | The measured window was under 30 seconds |

Plus whatever you add with `.expect(...)`:

```java
.expect(Objective.throughputAtLeast(300_000))
.expect(Objective.p99Below(Duration.ofMillis(50)))
.expect(Objective.noMessagesLost())
```

## Two things the broker taught us

**A synchronous confirmed publish costs a network round trip**, so a thread
doing one at a time is capped at about 550 messages a second against a broker
1.8ms away — whatever the broker is capable of. Reaching 300,000 that way would
need five hundred threads. Publishing asynchronously with a bounded window of
outstanding confirms decouples the offered rate from the round trip:

| | synchronous | windowed async |
|---|---|---|
| achieved (offered 2,000/s) | 1,048/s | **2,000/s** |
| send lag p50 | 4,209ms | **201µs** |
| end-to-end p50 | 4,209ms | **940µs** |

`maxInFlight` still bounds it, so when the broker stops confirming the window
fills, sends are delayed, and that delay is reported as send lag rather than
quietly disappearing.

**Percentiles cannot be averaged.** The mean of each thread's p99 is not the
p99, and neither is the mean of each second's p99. The samples have to meet in
one place — and at 300,000 a second for five minutes, a list of longs is 720MB
allocated during the measurement, which the collector then pays for in exactly
the pauses being measured. `HdrHistogram` solves both.

## What this is not

- **Not JMeter.** No GUI, no plugin ecosystem, no distributed mode. Rebuilding
  those is a multi-year scope and JMeter already exists.
- **Not a replacement for `rabbitmq-perf-test`**, which is maintained by the
  RabbitMQ team and battle-tested. What this adds is a Java DSL that lives in
  version control and runs in CI, topology discovery through the management API,
  and — the real reason it exists — the ability to put **AceMQ's own patterns**
  under load. Retry ladders, outboxes, sagas, claim checks and pipelines are
  things perftest cannot exercise, because it does not know they exist.
- **Not a capacity number for your production broker.** A container on a laptop
  sharing a CPU with the generator measures the laptop.

## Documentation

Seven guide pages and five tutorials, published at
**<https://acemq.org/acemq-java-amqp-workloads/>**. They read as markdown in
[docs/](docs/) too, and render with `.github/scripts/build-docs-site.sh`.

| | |
|---|---|
| **Start here** | [docs/index.md](docs/index.md) · [Getting started](docs/getting-started.md) |
| **Reference** | [Command line](docs/cli.md) · [Workload file](docs/workload-file.md) · [Scenario file](docs/scenario-file.md) · [Rules](docs/rules.md) · [Reports](docs/reports.md) |
| **Why the numbers hold** | [Measurement](docs/measurement.md) — the schedule arithmetic and coordinated omission |
| **Tutorials** | [Five, in order](docs/tutorials.md), each ending with something that runs |

## Requirements

Java 17. Docker for the integration tests.

## Licence

Apache-2.0. RabbitMQ is a trademark of Broadcom Inc.; this project is not
affiliated with it.
