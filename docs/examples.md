# Examples

Fourteen examples in [`examples/`](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/tree/main/examples),
simple to complex. Each one runs, each one was run before it shipped, and each
one carries its own comments — the file is the lesson, and this page is the tour.

The [tutorials](tutorials.md) teach the tool step by step. These are the other
shape: finished files to copy, adapt and argue with.

## Before you start

```bash
mvn -DskipTests package

docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:4-management
```

That gives you `library/target/acemq-workload.jar` and a broker on the port
every example points at. Every command on this page is run from the repository
root.

Each file resolves its password from the environment with a `guest` fallback, so
they work against a local broker untouched and against a real one with

```bash
export BROKER_PASSWORD=$(vault read -field=password secret/rabbit)
```

**One caveat that affects every number below.** The broker in that `docker run`
shares a CPU with the JVM generating the load. That is fine for learning what
each file does and useless as a capacity measurement — which is itself one of
the things these examples teach.

**One thing that will bite you once.** A broker refuses to redeclare an exchange
or a queue with different settings, so editing an example's exchange type or
queue type and running it again fails on the declaration rather than on
anything interesting:

```
acemq-workload: the run failed: could not declare exchange ex.bench
```

Delete the old one and run again — from the management interface, or:

```bash
curl -su guest:guest -X DELETE http://localhost:15672/api/exchanges/%2F/ex.bench
curl -su guest:guest -X DELETE http://localhost:15672/api/queues/%2F/ex.bench.quorum
```

Every name these examples use starts `ex.`, so they are easy to tell from
anything else on the broker.

## The ladder

| | | |
|---|---|---|
| 1 | [`01-hello.yaml`](#hello) | the smallest file that measures anything |
| 2 | [`02-objectives.yaml`](#objectives) | something to prove, and an exit code that means it |
| 3 | [`03-objective-missed.yaml`](#a-missed-objective) | what a failure looks like — exit 1 |
| 4 | [`04-broker-unreachable.yaml`](#the-other-two-exit-codes) | exit 4 and exit 3, in a second each |
| 5 | [`05-queue-types.yaml`](#classic-against-quorum) | classic against quorum, on the same messages |
| 6 | [`06-stream.yaml`](#a-stream) | a scenario, and why a stream is not a queue with a longer memory |
| 7 | [`07-fanout.yaml`](#fanout) | one publish, three deliveries, and which binding decides |
| 8 | [`08-topic-routing.yaml`](#topic-routing) | selective routing measured with several keys |
| 9 | [`09-slow-consumer.yaml`](#a-slow-consumer) | what a backlog does to every other number |
| 10 | [`10-ceiling.yaml`](#the-ceiling) | a suite, unthrottled, and why its latencies are worthless |
| 11 | [`11-invalid-run.yaml`](#a-run-that-measures-nothing) | exit 2, and why it is not exit 1 |
| 12 | [`12-ci-gate.yaml`](#a-gate) | the file a pipeline runs |
| 13 | [`13-dead-letter.yaml`](#dead-letters) | a handler that fails, and where those messages go |
| 14 | [`14-node-loss/`](#a-quorum-queue-losing-a-node) | three brokers, and one of them stops |

Output shown on this page is marked **real output** where it came from an actual
run and is reproduced with nothing but whitespace touched. Numbers from a laptop
are not numbers from your cluster; the shapes are what to read.

Each file carries a comment header explaining itself. Those headers are trimmed
from the listings below, because this page says the same things in prose.

---

## Hello

The smallest thing that measures anything: a queue, a rate, somebody to drain
it.

```yaml
name: hello
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5672

topology:
  queue: ex.hello
  routingKey: ex.hello

publishers:
  threads: 2
  rate: 5000
  messageSize: 512

consumers:
  concurrency: 4
  prefetch: 100

warmup: 5s
runFor: 30s
```

```bash
java -jar library/target/acemq-workload.jar -f examples/01-hello.yaml
```

Real output:

```
workload: hello
  TopologySpec{(default) -> ex.hello [ex.hello] classic}
  PublisherSpec{threads=2, rate=5000/s, Payload{512 bytes}, confirms=true}
  ConsumerSpec{concurrency=4, prefetch=100, handlerTime=PT0S}

  window        30s
  published     150,002  (5,000/s, offered 5,000/s)
  consumed      150,003  (5,000/s)
  queue at end  0

  ex.hello end-to-end n=150003    p50=847us     p90=1.8ms     p99=12.8ms    p99.9=32.9ms    max=60.5ms
  hello publish    n=150002    p50=1.2ms     p90=2.4ms     p99=8.8ms     p99.9=39.9ms    max=75.9ms
  hello send lag   n=150002    p50=49us      p90=85us      p99=2.6ms     p99.9=14.5ms    max=45.9ms

  [WARNING] tail-is-not-extreme
      observed:  p99 is 15 times the median (p50=847us, p99=12.8ms, max=60.5ms)
      means:     most messages are fast and a small fraction are far slower. A mean or median latency from this run would describe almost nobody's experience

  result: PASSED
```

Read it in this order: **send lag** (49µs — the generator kept its schedule, so
everything below is about the broker), **published against offered** (5,000
against 5,000), **end-to-end**, then **queue at end**.

The warning is the laptop talking. A p99 fifteen times the median means a small
fraction of messages waited far longer than the rest, which on this machine is
the container and the JVM competing for the same cores. It is worth seeing on
run one, because the same finding on a real broker is a real question.

Exit code `0`.

## Objectives

The same run with something to prove. Without `expect`, the tool can only
describe what happened, and a description is not something a pipeline can act
on.

```yaml
name: objectives
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5672
management: http://localhost:15672

topology:
  queue: ex.objectives
  routingKey: ex.objectives

publishers: { threads: 2, rate: 5000, messageSize: 512 }
consumers:  { concurrency: 4, prefetch: 100 }

warmup: 5s
runFor: 30s

expect:
  throughputAtLeast: 4800
  p99Below: 50ms
```

```bash
java -jar library/target/acemq-workload.jar -f examples/02-objectives.yaml --quiet
echo $?
```

Real output, from the full (non-quiet) run:

```
  window        30s
  published     150,001  (5,000/s, offered 5,000/s)
  consumed      150,003  (5,000/s)
  queue at end  0

  [INFO] throughput>=4800
      observed:  sustained 5,000 msg/s end-to-end over 30s
      means:     the objective was met
  [INFO] p99<50.0ms
      observed:  p99 was 2.3ms
      means:     within the 50.0ms budget

  result: PASSED
```

`throughputAtLeast` is set under the offered rate on purpose. An objective set
exactly at the rate you configured fails on rounding, and an objective that
fails for no reason is one people learn to ignore.

`management` is optional. The queue depth is read over AMQP during the run —
opening an HTTP connection every second would add load to the broker being
measured — and a management URL buys a more accurate final reading at the end.

Exit code `0`.

### Why `noMessagesLost` is not in that list

It belongs there and it is deliberately left out, because on a run like this one
it fails about half the time for a reason that has nothing to do with the broker.
Real output from this exact file with the objective added:

```
  published     150,008  (5,000/s, offered 5,000/s)
  consumed      150,006  (5,000/s)
  queue at end  0

  [FAILED] no-messages-lost
      observed:  150,008 messages were confirmed by the broker and 150,006 were
                 consumed, leaving 2 unaccounted for
      detail:    queue depth at the end: 0
```

Two messages out of a hundred and fifty thousand, and the finding's own detail
line disproves it: the queue is empty, so those two were delivered — the confirm
landed inside the measured window and the delivery was counted just after it.
The comparison carries no tolerance, so a boundary of a few messages reads
exactly like loss.

State it where losing messages is the thing under test —
[example 14](#a-quorum-queue-losing-a-node) does, and explains how to read its
finding — and read the queue depth in the finding before believing it. In a gate
that runs unattended, an objective that fails half the time for a reason that is
not the broker is worse than no objective at all.

## A missed objective

The same run with a budget no broker on a laptop will meet, so that a failure is
something you have seen before one happens to you.

```yaml
expect:
  p99Below: 200us
```

```bash
java -jar library/target/acemq-workload.jar -f examples/03-objective-missed.yaml --quiet
echo $?
```

Real output:

```
  [FAILED] p99<200us
      observed:  p99 was 2.6ms, against a budget of 200us
      means:     one message in 100 took longer than the budget allows
      detail:    p50=693us p99=2.6ms max=30.2ms

  result: FAILED

FAILED — every run was sound and at least one objective was not met
```

Exit code `1`, and the wording of that last line is the distinction the
[exit codes](cli.md#exit-codes-are-the-interface) exist for: **the measurement
was sound and the broker's answer was no.** The finding names both numbers,
because "p99 was 2.6ms" is a measurement and "p99 was 2.6ms against a budget of
200µs" is an answer.

## The other two exit codes

Three examples so far have exited 0, 1 and — later — 2. The remaining two are
not about the broker's performance at all, and neither takes half a minute,
because neither gets as far as measuring anything.

```yaml
name: broker-unreachable
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:1

topology:
  queue: ex.unreachable
  routingKey: ex.unreachable

publishers: { threads: 2, rate: 1000, messageSize: 512 }
consumers:  { concurrency: 2, prefetch: 100 }

warmup: 1s
runFor: 5s
```

Nothing is listening on port 1. That is the whole configuration.

```bash
java -jar library/target/acemq-workload.jar -f examples/04-broker-unreachable.yaml
echo $?
```

Real output:

```
running broker-unreachable against amqp://guest:***@localhost:1 ...
acemq-workload: the run failed: could not connect to amqp://guest:guest@localhost:1
```

Exit code `4`. It matters that this is not 1: a pipeline reading "the broker
refused the load" when the broker was never contacted sends somebody to look at
broker capacity while the real problem is a firewall rule, a wrong hostname, or
a container that has not finished starting.

For exit `3`, break the file. Misspell a setting:

```yaml
consumers:
  prefech: 100
```

Real output:

```
acemq-workload: unknown setting 'consumers.prefech'. Known settings here:
concurrency, prefetch, handlerTime, failureRate. A misspelled setting that was
ignored would run with the default and report a result that looks entirely
normal.
```

Exit code `3`, and no broker was contacted. Unknown settings are refused rather
than ignored for the reason the message gives: silently ignored, that typo runs
at the default prefetch and produces a full, plausible report for a
configuration nobody chose. A bare number in a duration is refused the same way
— `runFor: 60` is a minute to one reader and a second to another.

Neither of these ever becomes a pass by retrying, which is why a pipeline should
branch on all five codes rather than on zero-or-not.

`--dry-run` catches exit 3 in a second instead of after a two-minute wait, which
is why it is safe in a pre-commit hook:

```bash
java -jar library/target/acemq-workload.jar -f examples/04-broker-unreachable.yaml --dry-run
```

Real output — exit code `0`, because the file is valid; it is the broker that is
not:

```
broker-unreachable
  broker      amqp://guest:***@localhost:1
  TopologySpec{(default) -> ex.unreachable [ex.unreachable] classic}
  PublisherSpec{threads=2, rate=1000/s, Payload{512 bytes}, confirms=true}
  ConsumerSpec{concurrency=2, prefetch=100, handlerTime=PT0S}
  warmup      1s
  runFor      5s
  rules       7
```

One thing worth knowing while you are editing these files: **environment
substitution runs over the whole file before it is parsed, but it steps around
the comments.** So a `${NAME}` written inside a `#` comment as an example is
left exactly as written — it neither stops the run when the variable is unset
nor pastes the value in when it is set, which matters when the value is the
password the placeholder exists to keep out of the file. Outside a comment it
resolves as always, and an unset one with no default is still exit 3 rather
than a connection with an empty password.

## Classic against quorum

One fanout exchange, two queues bound to it, one producer. Every message reaches
both queues at the same instant, so the comparison is not two runs minutes apart
on a machine whose state moved in between — it is the same messages, the same
second, two storage engines.

```yaml
name: queue-types
description: what a quorum queue costs, measured against a classic one on the same messages
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5672
warmup: 5s
runFor: 30s

exchanges:
  - { name: ex.bench, type: fanout }

queues:
  - name: ex.bench.classic
    type: classic
    bindings: [ { exchange: ex.bench, routingKey: "" } ]
    consumers: { concurrency: 8, prefetch: 100 }
    expect:
      consumeRateAtLeast: 2800
      p99Below: 100ms
      noBacklog: true

  - name: ex.bench.quorum
    type: quorum
    bindings: [ { exchange: ex.bench, routingKey: "" } ]
    consumers: { concurrency: 8, prefetch: 100 }
    expect:
      consumeRateAtLeast: 2800
      p99Below: 100ms
      noBacklog: true

producers:
  - name: bench
    exchange: ex.bench
    routingKeys: [ "" ]
    rate: 3000
    messageSize: 1024
    expect:
      withinPercentOfOffered: 5
      noFailures: true
```

```bash
java -jar library/target/acemq-workload.jar -f examples/05-queue-types.yaml \
  --report reports/ --format md,html,json
```

Real output:

```
queue-types -- 30s
what a quorum queue costs, measured against a classic one on the same messages

producers
  bench                     3,000/s offered       3,000/s achieved  0 failed

queues
  ex.bench.classic     classic   8 consumers       3,000/s  p99 1ms  depth 0
  ex.bench.quorum      quorum    8 consumers       3,000/s  p99 4ms  depth 0

  result: PASSED
```

**Four times the p99, from the same messages in the same second.** That is a
sentence you can put in a design document, and it is what this whole tool is
for: the cost of the guarantee as a number rather than an opinion.

It is also a single node's answer and not the whole one. A quorum queue
replicates each message to a majority of nodes before confirming it, and on one
broker there is no majority to wait for — what you are paying for here is the
Raft log write alone. The cost of the guarantee on a real cluster is a cluster's
number, and [example 14](#a-quorum-queue-losing-a-node) is where that cluster
appears.

The 100ms budget is loose for a local broker on purpose. The absolute numbers
here belong to a laptop; the **gap between the two rows** is the result.

Exit code `0`.

### Why this is a scenario file and not a suite

Both work. A workload file's `queueType` and `arguments` reach the declaration,
so a suite of two workloads differing only in `queueType` is a real comparison —
`docs/workload-file.md` has one, and at 3,000/s it comes back with p99 2.4ms
classic against 38.3ms quorum.

They answer different questions, though. A suite runs the two queues one after
the other, each with the broker to itself. This file runs them together off one
fanout exchange, so the same message lands in both at the same instant — which
removes the minutes between two runs and the machine state that moved across
them, and adds the fact that each queue is now measured while the other is being
written. That is why the gap here is 4x and the gap in the suite is larger.
Neither is the true number; they are answers to different experiments, and the
one you want is whichever matches how the queue will actually be used.

The broker is the authority and the report is not — a report can only echo what
it was asked for. Check, on any file that claims a queue type:

```bash
curl -su guest:guest http://localhost:15672/api/queues/%2F/ex.bench.quorum
```

```json
{"name": "ex.bench.quorum", "type": "quorum",
 "arguments": {"x-queue-type": "quorum"}}
```

An earlier build dropped `queueType` between the file and the declaration, so
that same command answered `"type": "classic"` on the queue called `quorum`, and
a suite comparing the two compared classic with classic and passed. Fixed — and
the habit of asking the broker is worth keeping anyway.

## A stream

A **scenario** file rather than a workload file. A workload describes one path.
A scenario describes a topology — several queues with their own bindings, their
own consumers and their own objectives — and runs all of it at once. Nothing has
to say which kind of file it is: naming `queues` and `producers` is what decides.

```yaml
name: stream-alongside-classic
description: what an audit stream costs when it shares an exchange with the live path
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5672
warmup: 5s
runFor: 30s

exchanges:
  - { name: ex.events, type: fanout }

queues:
  - name: ex.events.live
    type: classic
    bindings: [ { exchange: ex.events, routingKey: "" } ]
    consumers: { concurrency: 4, prefetch: 100 }
    expect:
      p99Below: 50ms
      noBacklog: true

  - name: ex.events.audit
    type: stream
    bindings: [ { exchange: ex.events, routingKey: "" } ]
    consumers: { concurrency: 1, prefetch: 200 }
    expect:
      p99Below: 200ms

producers:
  - name: events
    exchange: ex.events
    routingKeys: [ "" ]
    rate: 4000
    messageSize: 1024
    expect:
      withinPercentOfOffered: 5
      noFailures: true
```

```bash
java -jar library/target/acemq-workload.jar -f examples/06-stream.yaml
```

Real output:

```
stream-alongside-classic -- 30s
what an audit stream costs when it shares an exchange with the live path

producers
  events                    4,000/s offered       4,000/s achieved  0 failed

queues
  ex.events.live       classic   4 consumers       4,000/s  p99 2ms  depth 0
  ex.events.audit      stream    1 consumers       4,000/s  p99 2ms  depth 127376 retained

  result: PASSED
```

`depth 0` against `depth 127376 retained`, from the same messages. The classic
queue's consumers acknowledged and the broker removed them; the stream's
consumer moved an offset and deleted nothing. The word is *retained* rather than
*waiting*, and `noBacklog` is not checked on a stream at all — asking a log not
to grow is a rule that fires on every run, which is a rule nobody reads.

The asymmetry is the reason a scenario exists. One overall p99 across both
queues would average away the only distinction worth keeping: the live path must
not fall behind, and the audit stream may lag as much as it likes.

Note that there is no `management:` line in this file or in any other scenario
here. A scenario reads queue depth over AMQP — a run with ten queues would
otherwise make ten HTTP requests a second to the broker it is measuring — so the
setting a workload file takes has no equivalent, and one written by the studio
is carried through the file without affecting a run.

Exit code `0`.

## Fanout

One publish, three deliveries. A fanout exchange copies every message to every
bound queue, so the broker's work is the publish rate times the number of
bindings — 3,000 offered here, 9,000 delivered.

The three queues are identical apart from how many consumers drain them.

```yaml
queues:
  - name: ex.notify.push
    consumers: { concurrency: 8, prefetch: 100, handlerTime: 1ms }
    expect: { p99Below: 500ms, noBacklog: true }

  - name: ex.notify.sms
    consumers: { concurrency: 8, prefetch: 100, handlerTime: 1ms }
    expect: { p99Below: 500ms, noBacklog: true }

  - name: ex.notify.email
    consumers: { concurrency: 2, prefetch: 100, handlerTime: 1ms }
    expect: { noBacklog: true }
```

Two consumers with a 1ms handler finish about 2,000 messages a second, and the
producer offers 3,000. That queue is going to lose, and losing on purpose is how
you find out what the report calls it.

```bash
java -jar library/target/acemq-workload.jar -f examples/07-fanout.yaml
echo $?
```

Real output:

```
producers
  notifier                  3,000/s offered       3,000/s achieved  0 failed

queues
  ex.notify.push       classic   8 consumers       3,000/s  p99 1ms  depth 0
  ex.notify.sms        classic   8 consumers       3,000/s  p99 1ms  depth 0
  ex.notify.email      classic   2 consumers       1,725/s  p99 14772ms  depth 44369

[WARNING] consumers-kept-up:ex.notify.email
    observed:  ex.notify.email grew from 6223 to 44369 messages over the run
    means:     its consumers took less than was published to it for the whole window, so the backlog was still growing when the run ended
[FAILED] expected-no-backlog:ex.notify.email
    observed:  ex.notify.email went from 6223 to 44369 messages, and was asked not to grow
    means:     work arrived faster than it was handled for the whole window

  result: FAILED
```

Exit code `1`.

**The publisher's line is perfect.** 3,000 offered, 3,000 achieved, nothing
failed — and one subscriber is fourteen seconds behind. A fanout is only as
healthy as its slowest binding, and a report that gave you one overall number
would have told you everything was fine.

Note also that `noBacklog` had to be *written* on `ex.notify.email` for this to
fail. What is not stated is not checked: an expectation nobody wrote is not one
the run failed.

**Run this one on an empty queue.** Nothing purges anything between runs, so a
second run starts with the first run's backlog still in `ex.notify.email`, and
its end-to-end latency counts from when *those* messages were due — a p99 in the
hundreds of seconds, which is true and not what you meant to measure. Delete the
queue in between:

```bash
curl -su guest:guest -X DELETE http://localhost:15672/api/queues/%2F/ex.notify.email
```

## Topic routing

The mistake this file exists to prevent: a producer publishing on one routing
key against a topic exchange with five bindings measures **one** binding. The
routing table is barely touched, the queues that do not match stay empty, and
the number you take away is for a topology you do not have.

`routingKeys` is a list and the producer uses the keys in turn, so the exchange
has to make a routing decision for every message.

```yaml
queues:
  - name: ex.orders.fulfilment
    type: quorum
    bindings: [ { exchange: ex.orders, routingKey: "order.*" } ]
    consumers: { concurrency: 8, prefetch: 200, handlerTime: 1ms }
    expect: { p99Below: 500ms, noBacklog: true }

  - name: ex.orders.audit
    type: classic
    bindings: [ { exchange: ex.orders, routingKey: "#" } ]
    consumers: { concurrency: 4, prefetch: 200 }
    expect: { p99Below: 500ms, noBacklog: true }

  - name: ex.orders.payments
    type: classic
    bindings: [ { exchange: ex.orders, routingKey: "payment.failed" } ]
    consumers: { concurrency: 2, prefetch: 100 }
    expect: { p99Below: 500ms }

producers:
  - name: checkout
    exchange: ex.orders
    routingKeys: [ order.created, order.paid, order.cancelled, payment.failed ]
    rate: 4000
    messageSize: 1024
```

Three of the four keys start `order.`, so `order.*` should see three quarters of
the traffic, `#` should see all of it, and `payment.failed` should see a quarter.

```bash
java -jar library/target/acemq-workload.jar -f examples/08-topic-routing.yaml
```

Real output:

```
producers
  checkout                  4,000/s offered       4,000/s achieved  0 failed

queues
  ex.orders.fulfilment quorum    8 consumers       3,000/s  p99 105ms  depth 0
  ex.orders.audit      classic   4 consumers       4,000/s  p99 6ms  depth 0
  ex.orders.payments   classic   2 consumers       1,000/s  p99 5ms  depth 0
```

3,000, 4,000 and 1,000 against 4,000 offered — the bindings do exactly what they
claim. **Check those proportions on your own topology.** If the narrow queue
shows the same total as the broad one, the bindings are not what you think they
are, and this run just found a topology bug for you.

The narrow binding is also the one worth stating a latency objective on. A queue
that received nothing fails a latency expectation, because its p99 is
unanswerable and unanswerable is not the same as passing — which is the check
that catches a typo in a binding key.

Exit code `0`.

## A slow consumer

Four consumers with a 5ms handler finish about 800 messages a second. The
publisher offers 4,000. Nothing is broken: the broker accepts everything, the
generator keeps its schedule, no publish fails. The queue simply grows.

```yaml
publishers: { threads: 4, rate: 4000, messageSize: 512 }
consumers:  { concurrency: 4, prefetch: 100, handlerTime: 5ms }

warmup: 5s
runFor: 30s
```

```bash
java -jar library/target/acemq-workload.jar -f examples/09-slow-consumer.yaml
echo $?
```

Real output:

```
  window        30s
  published     120,000  (4,000/s, offered 4,000/s)
  consumed      22,526  (751/s)
  queue at end  113,324

  ex.slow end-to-end n=22526     p50=16265.5ms p90=26021.5ms p99=28219.3ms p99.9=28437.4ms max=28454.2ms
  slow-consumer publish n=120000    p50=607us     p90=899us     p99=1.7ms     p99.9=3.6ms     max=6.5ms
  slow-consumer send lag n=120000    p50=112us     p90=125us     p99=135us     p99.9=206us     max=2.7ms

  [WARNING] consumers-kept-up
      observed:  120,000 messages were published and 22,526 consumed, leaving 97,474 (81%) still queued at the end
      means:     the consumers did not keep up, so the queue grew for the whole run. The end-to-end latency below is a function of the run's length rather than of the broker: a longer run would report a worse p99 from the same system
      detail:    consumer concurrency=4, prefetch=100, handler time=PT0.005S

  result: PASSED
```

Exit code `0`, and that is the lesson.

**Publish latency 607µs; end-to-end latency sixteen seconds.** The broker
accepted every message in well under a millisecond. Everything above that is the
queue. When end-to-end sits far above publish, the consumers are the slow part,
and no amount of broker tuning touches it.

**The p99 here is a property of the experiment.** Run it for sixty seconds
instead of thirty and the p99 roughly doubles, from the same broker and the same
consumers. A latency taken from a run in this state describes how long you ran.

**And nothing failed, because nothing was asked.** The warning is a warning
because a run that fills a queue on purpose is doing what it meant to. To make a
pipeline catch this, state it: `throughputAtLeast: 3800` exits 1 here, since
`throughputAtLeast` counts what was *consumed*.

## The ceiling

`unthrottled: true` removes the schedule — publish as fast as the confirms come
back. The achieved rate is the ceiling for this client, this broker and this
message size.

```yaml
consumers: { concurrency: 8, prefetch: 500 }

workloads:
  - name: ceiling-1kb
    topology: { exchange: ex.ceiling, queue: ex.ceiling.1kb, routingKey: k }
    publishers:
      threads: 4
      unthrottled: true
      messageSize: 1024
      confirms: true
      maxInFlight: 1000

  - name: ceiling-8kb
    topology: { exchange: ex.ceiling, queue: ex.ceiling.8kb, routingKey: k }
    publishers:
      threads: 4
      unthrottled: true
      messageSize: 8192
      confirms: true
      maxInFlight: 1000
```

```bash
java -jar library/target/acemq-workload.jar -f examples/10-ceiling.yaml \
  --report reports/ --format md
```

**Read the throughput and throw the latencies away.** With no schedule there is
no "due" time, so a stall in the broker also stalls the generator and the
recorded latency gets *better* as the stall gets worse. The report's offered
column says `unthrottled` instead of a rate, which is the tool declining to
pretend, and `generator-kept-up` does not run — there was no schedule to fall
behind.

Use it as step one of two:

1. Run this. Take the achieved rate.
2. Set `rate:` to about 70% of it in an ordinary workload and measure latency
   there. [`02-objectives.yaml`](#objectives) is that file.

A latency measured at the ceiling is a queue about to run away. A latency
measured below it is a number you can put in a sentence.

Watch `queue at end` while you do this. If it grows, the ceiling you found is
the consumers' and not the broker's.

### Two things about suites that are easy to get wrong

This is the one example that varies a field *inside* a block, so it is where
both traps live.

**Inheritance replaces a block; it does not merge into it.** An entry writing

```yaml
publishers: { messageSize: 4096 }
```

gets exactly that publishers block — `threads`, `unthrottled` and `maxInFlight`
revert to their defaults. The run is perfectly valid, the report looks entirely
normal, and it is not the experiment you wrote. So both blocks in the file are
written out in full and are meant to be read side by side with one field
different.

**Two entries must not share a routing key on a shared exchange.** `routingKey`
is both the publish key and the binding key, so the first entry's queue is still
bound when the second runs, and the second publishes into both. Nothing errors;
the consumed count is simply wrong by however much the first run left behind,
and it looks entirely plausible. An earlier draft of this page reported a
consumed total higher than the published total for exactly that reason.

## A run that measures nothing

One publisher thread holding one unconfirmed publish at a time. Every message
costs a full network round trip before the next may go out, so the thread
manages a few hundred a second — and the file asks for 5,000.

```yaml
publishers:
  threads: 1
  rate: 5000
  messageSize: 512
  confirms: true
  maxInFlight: 1

expect:
  throughputAtLeast: 4800
```

```bash
java -jar library/target/acemq-workload.jar -f examples/11-invalid-run.yaml
echo $?
```

Exit code `2`, with an `[INVALID] generator-kept-up` finding.

**2 is not 1, and a pipeline that treats them alike does the wrong thing with
each.** Exit 1 is an answer: the measurement was sound and the broker said no.
Exit 2 is the absence of an answer — the generator never offered the load, and
retrying this file unchanged produces the same non-answer forever. Sending
somebody to look at broker capacity on the strength of this run wastes their
afternoon; the broker is fine and the harness is the problem.

`INVALID` outranks `FAILED`, so the `throughputAtLeast` stated above is not what
the run is reported as failing. Reporting "5,000/s: FAILED" when the client
could only offer a few hundred would blame the wrong machine.

Watch what end-to-end latency does here: it lands near the send lag, because the
messages really were seconds late. A tool timestamping each message as it sent
it would have reported a couple of milliseconds and called the broker healthy.
That is [coordinated omission](measurement.md#coordinated-omission), and it is
the reason this tool measures from when a message was *due*.

The fix is in the file, not the broker: raise `maxInFlight` so a thread may hold
a window of unconfirmed publishes, and raise `threads`.
[`02-objectives.yaml`](#objectives) is the same rate with both, and it passes.

## A gate

The file a pipeline runs, and the shape most of the others are practice for.

```yaml
name: ci-gate
broker: amqp://${BROKER_USER:-guest}:${BROKER_PASSWORD:-guest}@localhost:5672
management: http://localhost:15672

topology:
  exchange: ex.gate
  exchangeType: topic
  queue: ex.gate.orders
  routingKey: order.created
  declare: true

publishers:
  threads: 8
  rate: 5000
  messageSize: 1024
  confirms: true
  maxInFlight: 2000

consumers:
  concurrency: 16
  prefetch: 200
  handlerTime: 1ms

warmup: 15s
runFor: 2m

expect:
  throughputAtLeast: 4800
  p99Below: 100ms
  p999Below: 500ms
```

```bash
java -jar library/target/acemq-workload.jar -f examples/12-ci-gate.yaml \
  --report "$RUNNER_TEMP/perf" --format html,json --quiet

case $? in
  0) ;;                                            # ship it
  1) echo "the broker did not meet the objective"; exit 1 ;;
  2) echo "the harness could not offer the load";  exit 1 ;;
  3) echo "this file is wrong";                    exit 1 ;;
  4) echo "could not reach the broker";            exit 1 ;;
esac
```

Upload the report on `always()`. The run you most want to look at is the one
that failed, and a gate that discards the evidence of its own failure is a gate
people turn off.

Four things make a gate one people keep:

**Objectives from the promise, not from yesterday's run.** 5,000/s and 100ms are
what the service says it does. A threshold copied from the last green run
ratchets down every time the runner is busy, and by March it certifies nothing.
If there is no promise to copy, that is the finding — go and get one before
writing a number here.

**Long enough to be a measurement.** Two minutes, not thirty seconds. The
`run-was-long-enough` warning exists because people reach for thirty seconds
when the pipeline feels slow, and a short run measures JIT, connection setup and
the first collection alongside the broker.

**Headroom in the generator, not in the objective.** `threads: 8` and
`maxInFlight: 2000` are there so the client is never the thing that fails. A
gate whose generator cannot keep up exits 2, which is noise, and noise is what
gets a gate switched off.

**Headroom in the consumers too, and this is the one people miss.** Sixteen
consumers with a 1ms handler finish about 16,000 a second, against 5,000
offered. Set them to eight and the arithmetic says 8,000 — which sounds like
ample headroom and is not. The first version of this file had `concurrency: 8`
against `rate: 8000`, and every objective in it failed at once:

```
  [FAILED] throughput>=7800
      observed:  sustained 7,179 msg/s end-to-end, against an objective of 7,800
      means:     this configuration delivers 92% of the required rate
  [FAILED] p99<100.0ms
      observed:  p99 was 13s, against a budget of 100.0ms
  [WARNING] consumers-kept-up
      observed:  960,022 messages were published and 861,523 consumed, leaving
                 98,499 (10%) still queued at the end
```

That is real output, from the version of this file that did not ship.
Acknowledgement, scheduling and the broker's own delivery cost ate the 800/s of
apparent headroom, the queue grew all run, and a p99 of thirteen seconds is what
a growing queue looks like from the far end. **A gate that fails this way tells
you nothing about the broker** — which is the lesson, and the reason the shipped
file runs at 5,000/s with sixteen consumers.

Against a real environment, set `declare: false`. Declaring would either be
refused for mismatched arguments or, worse, quietly create something subtly
different from what production runs and measure that instead.

There is no `queueType` in this file, so the gate tests a classic queue and says
so. Adding `queueType: quorum` makes it a quorum queue. What a gate must never
be is unsure which of the two it tested, so pin the type in the file rather than
leaving it to a default that could move under you.

Exit code `0` against a local broker.

## Dead letters

`failureRate: 0.05` makes one message in twenty throw out of the handler.
`deadLetterExchange` gives them somewhere to go.

```yaml
exchanges:
  - { name: ex.jobs, type: direct }
  - { name: ex.jobs.dead, type: fanout }

queues:
  - name: ex.work
    type: quorum
    bindings: [ { exchange: ex.jobs, routingKey: job } ]
    deadLetterExchange: ex.jobs.dead
    consumers:
      concurrency: 8
      prefetch: 100
      handlerTime: 1ms
      failureRate: 0.05
    expect: { p99Below: 500ms, noBacklog: true }

  - name: ex.dlq
    type: classic
    bindings: [ { exchange: ex.jobs.dead, routingKey: "" } ]
    consumers: { concurrency: 1, prefetch: 50 }
    expect: { noBacklog: true }

producers:
  - name: jobs
    exchange: ex.jobs
    routingKeys: [ job ]
    rate: 2000
    messageSize: 512
```

```bash
java -jar library/target/acemq-workload.jar -f examples/13-dead-letter.yaml
```

Real output:

```
producers
  jobs                      2,000/s offered       2,000/s achieved  0 failed

queues
  ex.work              quorum    8 consumers       2,000/s  p99 115ms  depth 0
  ex.dlq               classic   1 consumers          98/s  p99 117ms  depth 0

  result: PASSED
```

98/s out of 2,000/s — five percent, arriving where it was supposed to.

**Run this before trusting a dead-letter path, because the failure mode is
silent in both directions.** A dead-letter exchange that is not declared drops
what it is given. One bound to nothing accepts messages and discards them just
as thoroughly. Neither appears in a broker's error log, and both look exactly
like a handler that stopped failing. Here, a zero on the `ex.dlq` line would
have been the whole finding.

`ex.work` shows 2,000/s and `ex.dlq` shows 98/s, which add to more than was
published. That is correct: a delivery is counted when the handler receives it,
before it is given its chance to fail, so a dead-lettered message is counted
once on each queue.

Somebody has to drain the dead-letter queue, in the test as in production. A
dead-letter queue nobody consumes is a disk alarm with a delay on it.

Exit code `0`.

## A quorum queue losing a node

Everything so far ran against one broker. This one needs three, because a quorum
queue needs a quorum before it can lose part of one.

[`examples/14-node-loss/`](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/tree/main/examples/14-node-loss)
holds three files: a `compose.yaml` that starts a three-node cluster, a
`workload.yaml` aimed at node one, and a `run.sh` that ties them together.

```bash
CONTROL=1 ./examples/14-node-loss/run.sh    # the healthy cluster, first
./examples/14-node-loss/run.sh              # the same run, losing a node
```

**Do the control run first.** One measurement of a cluster losing a node is a
number with nothing to compare it against: p99.9 of 380ms means nothing until
you know what the same cluster does with nothing stopped. The difference between
the two runs is the answer; either figure alone is trivia.

The script starts the cluster and waits for all three nodes to agree that they
are one — a node that has started is not a node that has joined — then starts the
workload in the background, sleeps until the middle of the measured window, asks
the management API which node holds the queue's leader, and stops one of the
other two. It exits with the workload's exit code, so it works as a gate.

Only node one publishes its ports. That is deliberate: the generator connects to
node one and stays connected, so what you measure is the queue losing a replica
rather than your own client reconnecting. Those are two different experiments,
and mixing them produces a graph nobody can read.

It is a scenario file because of `x-quorum-initial-group-size`: how many
replicas there are, and how many may go away, is the whole subject of the run,
and a queue node is where a scenario puts an argument like that. A workload file
would carry it too — `topology.arguments` reaches the declaration — but a
workload is one queue and one path, and this wants the shape a scenario has.

```yaml
name: quorum-node-loss
broker: amqp://guest:${BROKER_PASSWORD:-guest}@localhost:5691
warmup: 10s
runFor: 60s

exchanges:
  - { name: ex.ha, type: direct }

queues:
  - name: ex.ha.orders
    type: quorum
    bindings: [ { exchange: ex.ha, routingKey: order } ]
    arguments:
      x-quorum-initial-group-size: 3
    consumers: { concurrency: 4, prefetch: 200 }
    expect:
      consumeRateAtLeast: 1800
      noBacklog: true

producers:
  - name: orders
    exchange: ex.ha
    routingKeys: [ order ]
    rate: 2000
    messageSize: 1024
    maxInFlight: 1000
    expect:
      noFailures: true
      withinPercentOfOffered: 5
```

Verify the queue is what the file says, because the broker is the authority:

```bash
curl -su guest:guest http://localhost:15691/api/queues/%2F/ex.ha.orders
```

Real output from the control run, trimmed to the fields that matter:

```json
{"name": "ex.ha.orders", "type": "quorum", "leader": "rabbit@node1",
 "members": ["rabbit@node1", "rabbit@node2", "rabbit@node3"]}
```

Three members, a leader, and a majority of two. **What a quorum queue does when
it loses one of three replicas is: not much, and that is the result.** Two nodes
remain, writes keep being confirmed, and the loss shows up as a bump in `p99.9`
and in `max` rather than in `p99` — only a second or two of a sixty-second
window is disturbed.

Real output from the control run:

```
quorum-node-loss -- 60s
what a quorum queue costs when one of its three replicas goes away

producers
  orders                    2,000/s offered       2,000/s achieved  0 failed

queues
  ex.ha.orders         quorum    4 consumers       2,000/s  p99 448ms  depth 0

  result: PASSED
```

### There is no latency budget in that file, and that is deliberate

Three replicas on one laptop are expensive before anything goes wrong, and how
expensive depends on what else the machine is doing. Two control runs on the
machine this was written on, same file, nothing stopped either time, came back
at **p99 94ms** and **p99 448ms**. Any number written into the file would be a
budget on the hardware rather than on the experiment, and a budget that fails
for reasons outside the thing under test is one people learn to ignore.

The objectives that *are* stated are the ones that belong to the experiment:

- **`noFailures`** on the producer is the one that must not bend. Losing a
  minority of replicas may make the queue slower; it may not make a confirmed
  publish fail. If this fires, the run found something that matters much more
  than a latency budget.
- **`noBacklog`** and **`consumeRateAtLeast`** say the queue must drain and the
  consumers must keep up — a queue that recovers and then stays behind has not
  recovered.

Add a latency budget from your own control run, which is the only place one can
honestly come from: read its p99 and p99.9, write `p99Below` and `p999Below` a
little above them, then run it again losing a node and see whether they hold.
The per-queue latency is reported either way, so the comparison is in front of
you before you write a single objective.

`noMessagesLost` is not stated here either, for the
[boundary reason above](#why-nomessageslost-is-not-in-that-list): it would fail
this run on a healthy cluster. `noFailures` on the producer is the objective
that carries the same meaning without the false positive.

Two things worth doing by hand once:

**Stopping the leader rather than a follower.** The script prints which node
holds the leader and then stops a different one, because that is the repeatable
experiment. Stopping the leader also costs an election, and the pause is longer.

**Losing a second node.** Two of three gone is no majority, and the queue stops
accepting writes until one comes back. That is not a slower queue, it is an
unavailable one, and it is what the third replica is for.

## Where to go next

- [Workload file](workload-file.md) — every setting, and why the awkward ones
  are the way they are
- [Scenario file](scenario-file.md) — the multi-node shape used from example 5
  onwards
- [Rules and objectives](rules.md) — what every run is checked for, whether you
  asked or not
- [Command line](cli.md) — reports, formats, and the exit codes a pipeline reads
- [How the measurement works](measurement.md) — why these latencies are worth
  more than the ones a loop-as-fast-as-you-can generator produces
