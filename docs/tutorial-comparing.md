# Tutorial 3 — Classic against quorum

**20 minutes.** A quorum queue is replicated and durable, and costs something.
This turns "costs something" into a number.

## Step 1 — A suite

The shared settings go at the top level and each entry overrides only what
differs. That is not tidiness: two copies of the same configuration that disagree
by one field make the comparison meaningless, and the file is the only place
anyone would notice.

```yaml
# compare.yaml
# Same load, same broker, same run length. Only the queue type differs.
broker: amqp://guest:guest@localhost:5672

publishers:
  threads: 4
  rate: 4000
  messageSize: 1024
  maxInFlight: 2000

consumers:
  concurrency: 8
  prefetch: 100

warmup: 5s
runFor: 60s

workloads:
  - name: classic-queue
    topology:
      exchange: bench
      exchangeType: topic
      queue: bench.classic
      routingKey: k
      queueType: classic

  - name: quorum-queue
    topology:
      exchange: bench
      exchangeType: topic
      queue: bench.quorum
      routingKey: k
      queueType: quorum
```

```bash
java -jar target/acemq-workload.jar -f compare.yaml --dry-run
```

Confirm both inherited the same publishers and consumers before running
anything. If they did not, the comparison is already worthless.

## Step 2 — Run it

```bash
java -jar target/acemq-workload.jar -f compare.yaml --report reports/ --format html,md,json
```

They run in order, against the same broker, and are reported together:

```
| workload      | result | offered | published | consumed | p50   | p99   | p99.9  |
| classic-queue | PASSED | 4,000/s | 4,000/s   | 4,000/s  | 1.4ms | 2.2ms | 5.3ms  |
| quorum-queue  | PASSED | 4,000/s | 4,000/s   | 4,000/s  | 1.1ms | 4.8ms | 47.7ms |
```

## Step 3 — Read it carefully

**Throughput is identical.** Both sustained the offered 4,000/s. At this rate the
queue type costs nothing in throughput, which is worth knowing and is not the
interesting part.

**p50 is a wash** — 1.4ms against 1.1ms, and the quorum queue is *faster* at the
median. Do not read anything into that; it is within the noise of a laptop.

**p99.9 is nine times worse**: 5.3ms against 47.7ms. That is the replication
showing up, and it shows up in the tail rather than the middle.

This is the shape of almost every durability trade-off, and it is why a mean or a
median would have told you nothing. A tool reporting "average latency 1.2ms vs
1.1ms" would have concluded the quorum queue was free.

## Step 4 — Make the cost concrete

Put a budget on it and let the exit code decide:

```yaml
workloads:
  - name: classic-queue
    topology: { exchange: bench, queue: bench.classic, routingKey: k, queueType: classic }
    expect: { p999Below: 20ms }

  - name: quorum-queue
    topology: { exchange: bench, queue: bench.quorum, routingKey: k, queueType: quorum }
    expect: { p999Below: 20ms }
```

```
| classic-queue | PASSED |
| quorum-queue  | FAILED |

[FAILED] p99.9<20.0ms
    observed:  p99.9 was 47.7ms, against a budget of 20.0ms
    means:     one message in 1,000 took longer than the budget allows
```

Exit 1. Now "can we afford quorum queues" has an answer that depends on your
budget rather than on anybody's intuition.

## Step 5 — Other comparisons worth making

The same suite shape answers several questions. Change one field, keep everything
else inherited:

**Do confirms cost us anything?**

```yaml
workloads:
  - name: with-confirms
    publishers: { threads: 4, rate: 4000, messageSize: 1024, confirms: true }
  - name: without-confirms
    publishers: { threads: 4, rate: 4000, messageSize: 1024, confirms: false }
```

The `confirms-were-on` warning fires on the second, which is correct — that
throughput counts messages handed to a socket rather than accepted by the broker.

**What does message size cost?**

```yaml
workloads:
  - name: bytes-512
    publishers: { threads: 4, rate: 4000, messageSize: 512 }
  - name: bytes-8k
    publishers: { threads: 4, rate: 4000, messageSize: 8192 }
  - name: bytes-64k
    publishers: { threads: 4, rate: 4000, messageSize: 65536 }
```

Use `randomPayload: true` for the large ones if anything in the path compresses.
A 64KB run of zeroes compresses to nothing and reports a throughput no real
payload will reproduce.

**What does prefetch cost?**

```yaml
workloads:
  - name: prefetch-1
    consumers: { concurrency: 8, prefetch: 1 }
  - name: prefetch-100
    consumers: { concurrency: 8, prefetch: 100 }
  - name: prefetch-unlimited
    consumers: { concurrency: 8, prefetch: 0 }
```

This one is worth doing with a realistic `handlerTime`, which is
[tutorial 5](tutorial-slow-consumer.md) — with an instant handler, prefetch
barely matters and the result will mislead you.

## Step 6 — The deliverable

The Markdown table is usually the whole thing:

```bash
java -jar target/acemq-workload.jar -f compare.yaml --report reports/ --format md,json --quiet
cat reports/workload-*.md | head -8
```

Paste it into the pull request that proposes the change. The JSON goes next to it
so somebody can check the numbers rather than trusting the table.

## What you have

- A suite where only the interesting field differs
- The cost of a quorum queue as a number, in the tail where it lives
- Why a mean would have reported the opposite conclusion
- The same shape for confirms, message size and prefetch

Next: [failing a build](tutorial-ci-gate.md).
