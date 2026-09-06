# Scenario file

A workload file describes one path: an exchange, a queue, publishers, consumers.
A **scenario** file describes a whole topology — several exchanges, several
queues with their own bindings and their own consumer counts, several producers —
and runs all of it at once.

This is the file the [studio](../studio/README.md) exports, and the same `-f`
reads it:

```bash
java -jar acemq-workload.jar -f acemq-workload-orders-2026-09-06.json
```

Nothing has to say which kind of file it is. A scenario names `exchanges`,
`queues` and `producers`; a workload names a `topology` with `publishers` and
`consumers`. That difference is not cosmetic, and it is enough to decide.

## The shape

JSON or YAML — the same fields either way.

```yaml
name: orders-peak
description: what the fulfilment leg costs when audit is behind
broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
warmup: 10s
runFor: 2m
declare: true

exchanges:
  - { name: orders, type: topic }

queues:
  - name: orders.shipping
    type: quorum
    bindings: [ { exchange: orders, routingKey: "order.*" } ]
    consumers: { concurrency: 8, prefetch: 200, handlerTime: 1ms }
    expect:
      p99Below: 50ms
      noBacklog: true

  - name: orders.audit
    type: stream
    bindings: [ { exchange: orders, routingKey: "#" } ]
    consumers: { concurrency: 1, prefetch: 100 }

producers:
  - name: checkout
    exchange: orders
    routingKeys: [ order.placed, order.cancelled ]
    rate: 20000
    messageSize: 1024
    expect:
      withinPercentOfOffered: 5
      noFailures: true
```

| Field | |
|---|---|
| `name` | required |
| `description` | what question this run answers |
| `broker` | the AMQP URL; `--broker` on the command line wins |
| `warmup`, `runFor` | `500ms`, `10s`, `2m`. Default `5s` and `30s` |
| `declare` | whether to declare the topology first. Turn it **off** against a real environment |
| `exchanges[]` | `name`, `type`, `durable`, `enabled`, `arguments` |
| `queues[]` | `name`, `type`, `bindings[]`, `consumers`, `deadLetterExchange`, `arguments`, `expect` |
| `producers[]` | `name`, `exchange`, `routingKeys[]`, `rate`, `threads`, `messageSize`, `confirms`, `maxInFlight`, `maxMessages`, `expect` |

Queue types are `classic`, `classic-mirrored`, `quorum` and `stream`. Several
routing keys on a producer are used in turn, which is what makes a topic exchange
behave like one: a producer on a single key measures one binding however many the
exchange has.

`enabled: false` on anything keeps its settings and leaves it out of the run —
what you were about to put back is still there, prefetch and all.

## Objectives

`expect` is what a node must **prove**. Without it a scenario can only describe
what happened, and a pipeline cannot act on a description.

**Per node, not for the whole run.** The interesting property is usually
asymmetric: above, the audit stream may lag as much as it likes while the
fulfilment queue must not. One overall p99 would average away exactly the
distinction worth keeping.

On a queue:

| | |
|---|---|
| `p50Below`, `p99Below`, `p999Below` | end-to-end latency, from when a message was **due** |
| `consumeRateAtLeast` | messages a second this queue's consumers must handle |
| `noBacklog` | it must not be deeper at the end than at the start |

On a producer:

| | |
|---|---|
| `achievedRateAtLeast` | messages a second it must actually offer |
| `withinPercentOfOffered` | how far the achieved rate may fall short of the configured one, as a percentage |
| `noFailures` | every publish must succeed |

Everything is optional, and **what is not stated is not checked** — an
expectation nobody wrote is not one the run failed. Anything that is stated
produces a `FAILED` finding naming the node and both numbers:

```
[FAILED] expected-p99:orders.shipping
    observed:  orders.shipping p99 was 91.4ms, and was asked for under 50ms
    means:     the queue answered, and answered more slowly than this run required
```

and the process exits `1`. The [exit codes](cli.md#exit-codes-are-the-interface)
are the same as a workload's, because a pipeline should not have to care which
kind of file it was given.

Two cases worth knowing:

- **A queue that received nothing fails a latency expectation.** Its p99 is
  unanswerable, and unanswerable is not the same as passing.
- **A stream is never accused of a backlog.** Consumers move an offset and
  nothing is removed, so a stream's depth is the length of its log. `noBacklog`
  is not checked for one, and the report says "retained" rather than "waiting".

## Secrets

`${VAR}` and `${VAR:-default}` come from the environment, so a password never has
to live in a file that gets committed. An unset variable with no default is an
error rather than an empty password.

The studio **does not** resolve these when it opens a file. Doing so would read
the studio's own environment on behalf of whoever uploaded the file and hand the
value back, so the placeholder stays a placeholder and is resolved by the command
line, where the person running it owns the environment.

## Checking one without running it

```bash
java -jar acemq-workload.jar -f scenario.json --dry-run
```

Prints the topology, the objectives and the problems — a binding pointing at an
exchange nothing declares, a producer aimed at nothing — with the password
redacted, and touches no broker. Left to the broker, those arrive mid-run as a
channel closure that reads like a broker problem.

## Reports

`--report <dir>` writes `html`, `md` and `json`, the same as a workload, with a
table of queues and a table of producers rather than one row. Each node carries
what it was asked for alongside what it did: "p99 was 41ms" is a number, and
"p99 was 41ms against the 50ms this required" is an answer.
