# Tutorial 4 — Failing a build

**20 minutes.** A performance gate that fails for the right reason, and — more
importantly — does not fail for the wrong one.

## Step 1 — The four ways it can end

```bash
java -jar target/acemq-workload.jar -f workload.yaml --quiet
echo $?
```

| | |
|---|---|
| `0` | passed |
| `1` | a **sound** run missed an objective |
| `2` | the run was **invalid** — nothing was measured |
| `3` | the workload file is wrong |
| `4` | the broker could not be reached |

Most tools collapse these into "pass" and "fail". That is the mistake, and it
produces two specific bad outcomes:

- **A build that cannot tell 1 from 2** retries a run that can never succeed,
  because the generator was the bottleneck and will be again.
- **A build that cannot tell 4 from 1** reports "the broker did not meet the
  objective" when the broker was never contacted, and sends somebody to look at
  capacity while a firewall rule is the actual problem.

## Step 2 — See each one

**Exit 1 — a sound run, an objective missed:**

```yaml
# gate.yaml
name: gate
broker: amqp://guest:guest@localhost:5672
topology: { queue: tut.gate, routingKey: tut.gate }
publishers: { threads: 4, rate: 4000, messageSize: 512 }
consumers:  { concurrency: 4, prefetch: 100 }
warmup: 3s
runFor: 30s
expect:
  throughputAtLeast: 500000
```

```
FAILED — every run was sound and at least one objective was not met
exit: 1
```

**Exit 2 — nothing measured.** Ask one thread for three million a second:

```yaml
publishers: { threads: 1, rate: 3000000, messageSize: 1024 }
expect:
  throughputAtLeast: 3000000
```

```
INVALID — at least one run did not measure what it was asked to
exit: 2
```

Note it is **not** 1. The objective was missed, and saying so would be blaming
the broker for the client's limit.

**Exit 3 — a bad file:**

```yaml
consumers: { prefech: 100 }
```

```
acemq-workload: unknown setting 'consumers.prefech'. ...
exit: 3
```

**Exit 4 — no broker:**

```yaml
broker: amqp://guest:guest@127.0.0.1:1
```

```
acemq-workload: the run failed: ...
exit: 4
```

## Step 3 — A gate that acts on the difference

```bash
#!/usr/bin/env bash
set -uo pipefail

java -jar acemq-workload.jar -f perf/nightly.yaml \
  --report "${REPORT_DIR:-reports}" --format html,json --quiet
status=$?

case $status in
  0) echo "::notice::performance gate passed" ;;
  1) echo "::error::the broker did not meet the objective — this is a real regression" ;;
  2) echo "::error::the load generator could not offer the configured rate."
     echo "::error::This says nothing about the broker. Fix the harness: more"
     echo "::error::publisher threads, a larger maxInFlight, or a bigger runner." ;;
  3) echo "::error::the workload file is invalid" ;;
  4) echo "::error::the broker was unreachable — check the service, not its capacity" ;;
esac

exit $status
```

The messages matter as much as the codes. Whoever is woken by this at 3am reads
the message, and "fix the harness" versus "this is a real regression" are
different nights.

## Step 4 — In GitHub Actions

```yaml
name: performance

on:
  schedule: [{ cron: "0 3 * * *" }]
  workflow_dispatch:

jobs:
  load-test:
    runs-on: ubuntu-latest
    services:
      rabbit:
        image: rabbitmq:4-management
        ports: ["5672:5672", "15672:15672"]
        options: >-
          --health-cmd "rabbitmq-diagnostics -q ping"
          --health-interval 10s --health-retries 10

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }

      - run: mvn -B -DskipTests package

      - name: Load test
        run: bash perf/gate.sh
        env:
          REPORT_DIR: ${{ runner.temp }}/perf
          BROKER_PASSWORD: ${{ secrets.BROKER_PASSWORD }}

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: load-test-report
          path: ${{ runner.temp }}/perf
```

`if: always()` on the upload is not optional. The run you most want to read is
the one that failed, and a gate that discards its own evidence on failure will be
switched off within a month.

## Step 5 — Setting an objective that will not lie to you

This is where performance gates usually go wrong. Some rules learned the hard
way, one of them in this very project:

**Do not set a budget tighter than your measurement error.** A gate enforcing 5%
on a measurement that varies by 10% between identical runs fails half the time
for no reason, and then everybody ignores it. If two consecutive runs of the same
configuration differ by 15%, your budget cannot be 5%.

**Run long enough.** Under thirty seconds you are measuring JIT compilation and
connection setup, and `run-was-long-enough` will say so. For a gate, minutes.

**Do not compare across machines.** A baseline captured on a laptop and enforced
on a CI runner is comparing two computers. Capture the baseline where the gate
runs.

**Set the objective from a measurement, not a wish.** Run the workload ten times,
take the worst, and leave headroom. An objective derived from what the system
actually does fails when something changes; one derived from what somebody hoped
fails on day one.

**Gate on the shape you care about.** Throughput and p99 together are usually
right. Throughput alone passes a system whose tail has quietly doubled.

```yaml
expect:
  throughputAtLeast: 18000    # measured 21,400 worst of ten; 15% headroom
  p99Below: 25ms              # measured 14ms worst of ten
  noMessagesLost: true
```

Note the comments. In six months the question will be "why 18,000?", and the file
is the only place that can answer.

## Step 6 — Gating a whole topology, not one path

Everything above gates a workload: one exchange, one queue, one set of
publishers. A [scenario](scenario-file.md) gates the shape a system actually
has, and the same command runs it — which kind of file it is, is decided by what
is in it.

The reason to bother is that the interesting property is nearly always
asymmetric:

```yaml
name: orders-peak
exchanges:
  - { name: orders, type: topic }
queues:
  - name: orders.shipping
    type: quorum
    bindings: [ { exchange: orders, routingKey: "order.*" } ]
    consumers: { concurrency: 8, prefetch: 200 }
    expect:
      p99Below: 50ms        # measured 31ms worst of ten
      noBacklog: true
  - name: orders.audit
    type: stream
    bindings: [ { exchange: orders, routingKey: "#" } ]
    consumers: { concurrency: 1 }
    # Nothing. Audit is allowed to lag; that is the whole point of putting it
    # behind its own queue.
producers:
  - name: checkout
    exchange: orders
    routingKeys: [ order.placed, order.cancelled ]
    rate: 20000
    expect:
      withinPercentOfOffered: 5
      noFailures: true
runFor: 2m
```

A single overall p99 across both legs would average the audit stream's lag into
the fulfilment queue's number and pass a build that should have failed.

```
[FAILED] expected-p99:orders.shipping
    observed:  orders.shipping p99 was 91.4ms, and was asked for under 50ms
    means:     the queue answered, and answered more slowly than this run required
```

Exit `1`, and the finding names the queue. Two things worth knowing before you
rely on it:

- **`withinPercentOfOffered` on the producer is the guard against a lying gate.**
  It is the same distinction as exit 1 against exit 2, expressed per producer: if
  the load was never applied, the queue numbers describe the generator.
- **A queue that received nothing fails a latency expectation** rather than
  passing it. A binding typo would otherwise be a green build.

The studio can draw this and export it, so the file a pipeline runs is the file
somebody designed rather than one they transcribed. Its presets ship with their
objectives set, which is the quickest way to see what a gated scenario looks
like.

## Step 7 — Keeping the JSON

```bash
java -jar acemq-workload.jar -f perf/nightly.yaml --report reports/ --format json --quiet
```

Every run appends a timestamped file. Graph `consumeRatePerSecond` and
`endToEnd.p99Micros` over time and a slow regression becomes visible long before
it crosses a threshold — which is the failure mode a pass/fail gate cannot catch
by construction.

## What you have

- Four exit codes and a gate that treats them differently
- Messages that tell the reader whether to fix the broker or the harness
- Objectives derived from measurements, with the reasoning in the file
- A report that survives the failure that produced it

Next: [when the consumer is the problem](tutorial-slow-consumer.md).
