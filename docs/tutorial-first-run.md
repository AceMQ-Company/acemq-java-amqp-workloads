# Tutorial 1 — Your first measurement

**15 minutes.** Run a workload, then understand every line of what comes back.

Start the broker from the [tutorials page](tutorials.md) if it is not running.

## Step 1 — A workload file

```yaml
# first.yaml
name: first-run
broker: amqp://guest:guest@localhost:5672

topology:
  queue: tut.hello
  routingKey: tut.hello

publishers:
  threads: 2
  rate: 2000
  messageSize: 512

consumers:
  concurrency: 4
  prefetch: 100

warmup: 5s
runFor: 30s
```

No exchange, so publishes go through the default exchange, which routes by queue
name. It is the fastest path and not what most systems do — tutorial 3 uses a
real exchange.

## Step 2 — Check it before running it

```bash
java -jar target/acemq-workload.jar -f first.yaml --dry-run
```

```
first-run
  broker      amqp://guest:***@localhost:5672
  TopologySpec{(default) -> tut.hello [tut.hello] classic}
  PublisherSpec{threads=2, rate=2000/s, Payload{512 bytes}, confirms=true}
  ConsumerSpec{concurrency=4, prefetch=100, handlerTime=PT0S}
  warmup      5s
  runFor      30s
  rules       7
```

No broker was touched. Worth the habit: a typo caught here costs a second, and
the same typo caught after a two-minute run costs two minutes and your attention.

Try breaking it on purpose:

```yaml
consumers:
  prefech: 100      # note the typo
```

```
acemq-workload: unknown setting 'consumers.prefech'. Known settings here:
concurrency, prefetch, handlerTime, failureRate. A misspelled setting that was
ignored would run with the default and report a result that looks entirely
normal.
```

That is the point. Silently ignored, it would have run at the default prefetch
and produced a perfectly plausible report for a configuration you did not choose.

## Step 3 — Run it

```bash
java -jar target/acemq-workload.jar -f first.yaml
```

```
running first-run against amqp://guest:***@localhost:5672 ...
workload: first-run

  window        30s
  published     60,001  (2,000/s, offered 2,000/s)
  consumed      60,001  (2,000/s)
  queue at end  0

  end-to-end       n=60001  p50=940us  p90=1.2ms  p99=3.5ms  p99.9=11.0ms  max=14.7ms
  publish          n=60001  p50=718us  p90=955us  p99=1.7ms  p99.9=7.0ms   max=11.2ms
  send lag         n=60001  p50=201us  p90=239us  p99=941us  p99.9=7.0ms   max=12.8ms

  no findings

  result: PASSED
```

## Step 4 — Read it in the right order

### First: send lag

```
send lag         p50=201us  p99=941us
```

**How far behind its own schedule each publish went out.** Message 1000 was due
at 500ms; it went out at 500.2ms.

Read this *first*, always. If it were seconds instead of microseconds, the
generator never offered 2,000/s, and nothing below would be about the broker. The
tool would say so — but knowing why is what makes the rest trustworthy.

### Then: published vs offered

```
published     60,001  (2,000/s, offered 2,000/s)
```

Achieved equals offered. The schedule held.

### Then: end-to-end

```
end-to-end       p50=940us  p99=3.5ms  p99.9=11.0ms
```

From when a message was **due** to when a consumer had it. Not from when it was
sent — that difference is the whole reason to trust this number, and
[measurement](measurement.md) explains why.

Note the shape: half the messages under a millisecond, one in a hundred over
3.5ms, one in a thousand over 11ms. That spread is normal and it is why the mean
is not printed first.

### Then: publish

```
publish          p50=718us  p99=1.7ms
```

The confirm round trip — the broker accepting the message. Compare it with
end-to-end: 718µs to be accepted, 940µs to reach a consumer, so about 220µs of
that latency is queueing and delivery. When end-to-end is far above publish, the
consumers are the slow part.

### Finally: queue at end

```
queue at end  0
```

The consumers kept up. Anything else means the queue grew and the latency figures
are a function of how long you ran.

## Step 5 — Add an objective

```yaml
expect:
  throughputAtLeast: 1900
  p99Below: 10ms
```

```bash
java -jar target/acemq-workload.jar -f first.yaml --quiet
echo "exit: $?"
```

```
PASSED — 1 workload
exit: 0
```

Now tighten it until it fails:

```yaml
expect:
  p99Below: 1ms
```

```
FAILED — every run was sound and at least one objective was not met
exit: 1
```

```
[FAILED] p99<1.0ms
    observed:  p99 was 3.5ms, against a budget of 1.0ms
    means:     one message in 100 took longer than the budget allows
    detail:    p50=940us p99=3.5ms max=14.7ms
```

Exit 1, not 2 — the measurement was sound and the answer is no. Tutorial 4 is
about why that distinction matters.

## Step 6 — Write a report

```bash
java -jar target/acemq-workload.jar -f first.yaml --report reports/
open reports/workload-*.html
```

Two files: HTML for reading and JSON for anything else. The HTML embeds the JSON,
so forwarding the report keeps the data with it.

## What you have

- A workload that runs, and a report you can read line by line
- The habit of checking send lag before anything else
- Why a typo is an error rather than a default
- An objective that fails the exit code

Next: [finding the ceiling](tutorial-finding-the-ceiling.md) — how much this
broker can actually take, and how to tell its limit from your own.
