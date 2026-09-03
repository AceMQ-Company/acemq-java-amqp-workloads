# Tutorial 5 — When the consumer is the problem

**25 minutes.** A queue is backing up. Is the broker slow, or is your handler?

The report separates them, and a realistic `handlerTime` is what makes the
question answerable at all.

## Step 1 — A handler that does nothing lies to you

```yaml
# consumer.yaml
name: instant-handler
broker: amqp://guest:guest@localhost:5672
topology: { queue: tut.consumer, routingKey: tut.consumer }
publishers: { threads: 4, rate: 4000, messageSize: 512 }
consumers:  { concurrency: 4, prefetch: 100, handlerTime: 0s }
warmup: 3s
runFor: 30s
```

```
  published     120,000  (4,000/s)
  consumed      120,000  (4,000/s)
  queue at end  0
  end-to-end    p50=0.9ms  p99=2.4ms
```

Everything is comfortable, and this measured the broker. Almost no production
consumer does nothing — the moment yours calls a database, this result stops
describing it.

## Step 2 — Give the handler work

```yaml
consumers:
  concurrency: 4
  prefetch: 100
  handlerTime: 5ms
```

Four consumers at 5ms each is 800 messages a second of capacity, against 4,000
offered.

```
  published     120,000  (4,000/s, offered 4,000/s)
  consumed      24,131   (804/s)
  queue at end  95,869

  end-to-end       p50=48.2s   p90=86.1s   p99=95.4s   max=97.8s
  publish          p50=712us   p90=934us   p99=1.6ms   max=8.9ms
  send lag         p50=203us   p90=241us   p99=602us   max=4.1ms

  [WARNING] consumers-kept-up
      observed:  120,000 messages were published and 24,131 consumed, leaving 95,869
                 (80%) still queued at the end
      means:     the consumers did not keep up, so the queue grew for the whole run.
                 The end-to-end latency below is a function of the run's length rather
                 than of the broker: a longer run would report a worse p99 from the
                 same system
      detail:    consumer concurrency=4, prefetch=100, handler time=PT0.005S
```

## Step 3 — The diagnosis is in the gap

Look at the three histograms side by side:

| | |
|---|---|
| **send lag** | 203µs — the generator kept its schedule perfectly |
| **publish** | 712µs — the broker accepted every message immediately |
| **end-to-end** | 48 seconds |

**The broker is fine.** It took every message in under a millisecond. The 48
seconds is entirely time spent sitting in the queue waiting for a consumer.

This is the separation the tool exists to make. A monitoring dashboard showing
"queue depth rising, latency 48s" cannot tell you whether the broker or the
application is at fault. These three numbers can.

And notice what the warning says about the 48 seconds: it is a property of the
run's *length*, not of the system. Run for two minutes instead of thirty seconds
and the p99 becomes four times worse from an unchanged setup. Never quote a
latency figure from a run whose queue was growing.

## Step 4 — Fix it the honest way

The consumers need capacity. 4,000/s at 5ms per message needs 20 concurrent
consumers:

```yaml
consumers:
  concurrency: 20
  prefetch: 100
  handlerTime: 5ms
```

```
  published     120,000  (4,000/s)
  consumed      119,994  (4,000/s)
  queue at end  6

  end-to-end       p50=6.1ms   p90=7.4ms   p99=11.2ms
```

Now end-to-end is roughly `handlerTime` plus a millisecond of broker, which is
what a healthy system looks like: the application is the slow part, and it is
keeping up anyway.

## Step 5 — What prefetch actually does

Now that the handler takes real time, prefetch matters. Compare:

```yaml
# prefetch.yaml
broker: amqp://guest:guest@localhost:5672
topology: { queue: tut.prefetch, routingKey: tut.prefetch }
publishers: { threads: 4, rate: 3000, messageSize: 512 }
warmup: 3s
runFor: 30s

workloads:
  - name: prefetch-1
    consumers: { concurrency: 20, prefetch: 1, handlerTime: 5ms }
  - name: prefetch-10
    consumers: { concurrency: 20, prefetch: 10, handlerTime: 5ms }
  - name: prefetch-unlimited
    consumers: { concurrency: 20, prefetch: 0, handlerTime: 5ms }
```

```
| workload           | consumed | p50    | p99     | p99.9   |
| prefetch-1         | 3,000/s  | 6.2ms  | 9.1ms   | 14.0ms  |
| prefetch-10        | 3,000/s  | 6.0ms  | 8.4ms   | 12.7ms  |
| prefetch-unlimited | 3,000/s  | 41.8ms | 210.4ms | 480.2ms |
```

All three keep up. **Unlimited prefetch is five times worse at the median and
twenty times worse in the tail** — because one consumer grabs everything
available the moment it connects, and the messages it is sitting on wait behind
its queue of work while other consumers idle.

That is the argument for a bounded prefetch, and it is invisible with
`handlerTime: 0s`. With an instant handler all three rows look identical, which
is why step 1 matters.

## Step 6 — Consumers that fail

```yaml
consumers:
  concurrency: 20
  prefetch: 10
  handlerTime: 5ms
  failureRate: 0.05
```

Five percent of messages are rejected, which exercises retry and dead-lettering
under load — where those paths behave least like they do in a unit test. Watch
`queue at end` and the queue in the management UI: a retry policy that looks fine
at ten messages a second can be a different shape at three thousand.

## What you have

- Three histograms that separate a slow broker from a slow handler
- Why a latency figure from a growing queue describes the run's length
- What prefetch costs when the handler does real work, and why the default
  measurement hides it
- The sizing arithmetic: `concurrency ≥ rate × handlerTime`

## Where to go next

- [Measurement](measurement.md) — why the latency numbers are trustworthy
- [Rules](rules.md) — every check, and how to write your own
- [Command line](cli.md) — reports and exit codes
