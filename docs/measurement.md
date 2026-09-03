# How the measurement works

The part worth understanding before trusting any number this tool prints.

## Coordinated omission

Suppose the schedule says publish at t = 0, 1ms, 2ms, 3ms, and the broker stalls
for 100ms after the first message.

A generator that loops as fast as it can, timestamping each message as it sends
it, records four messages that each took a millisecond or two. **The stall is
invisible.** The generator was stalled with it, never offered the load it was
supposed to, and reported the broker's service time rather than the client's
wait.

The worse the stall, the better the numbers look. This is *coordinated
omission*, and it is the reason most homemade load tools produce latency figures
nobody should act on.

## What this tool does instead

Message *n* is due at `start + n × interval`, **computed from the start** rather
than from the previous send:

```java
long intended = start + sequence * intervalNanos;
long waitFor = intended - System.nanoTime();
if (waitFor > 0) {
    LockSupport.parkNanos(waitFor);
}
```

Adding the interval to *now* after each publish is the mistake that turns this
into a closed loop: every millisecond the broker takes pushes the whole remaining
schedule back, and the offered rate silently becomes whatever the broker allows.

Each message carries its intended time in a 16-byte header, and the consumer
measures from that:

```java
endToEnd.record(System.nanoTime() - Payload.intendedSendNanos(body));
```

So the same 100ms stall produces latencies of 100ms, 99ms, 98ms, 97ms — which is
what a client on that schedule actually experienced.

## Send lag

The gap between *due* and *actually sent*, reported as its own histogram.

This is the number that decides whether the run measured anything. If publishes
went out four seconds behind schedule, the configured rate was never offered, and
every other figure in the report describes the client rather than the broker.

```
send lag         n=20002  p50=201us  p90=239us  p99=941us  max=12.8ms
```

Above 100ms at p99 the run is marked [INVALID](rules.md).

Send lag is also honest back-pressure. When the broker stops confirming, the
in-flight window fills, the next publish waits, and that wait lands here rather
than disappearing.

## Why a synchronous publish cannot generate load

A publish that waits for its own confirm costs one network round trip. A thread
doing that is capped at one message per round trip, no matter what the broker can
take.

Measured against the same broker, offering 2,000/s:

| | one at a time | windowed async |
|---|---|---|
| achieved | 1,048/s | **2,000/s** |
| send lag p50 | 4,209ms | **201µs** |
| end-to-end p50 | 4,209ms | **940µs** |
| verdict | INVALID | PASSED |

At 1.8ms per round trip a thread manages about 550 a second. Reaching 300,000
that way would need five hundred threads.

Note that in the broken run **end-to-end p50 equals send lag p50 exactly**. That
is the intended-time measurement working: the messages really were 4.2 seconds
late, and a tool timestamping at send would have reported 1.8ms and called it
healthy.

`maxInFlight` bounds the window, so back-pressure still exists — it is simply
measured instead of hidden.

## Percentiles

Kept in an [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram), for two
reasons that are each fatal to the alternative.

**Percentiles cannot be averaged.** The mean of each thread's p99 is not the p99.
Neither is the mean of each second's p99. Any design that summarises per thread
or per interval and combines the summaries produces a number that is not a
percentile of anything. The samples have to meet in one place.

**A list does not fit.** At 300,000 a second for five minutes that is ninety
million longs — 720MB, allocated *during* the measurement, which the garbage
collector then pays for in exactly the pauses being measured.

The report shows p50, p90, p99, p99.9 and max. The mean is available and
deliberately not printed first: it is the number least likely to describe
anybody's experience, because it hides the tail entirely.

### One percentile subtlety

A tail narrower than the percentile you are asking about does not appear in it.
With 990 fast samples and 10 slow ones, the slow ones are exactly the top 1% and
`p99` sits on the boundary — it returns the fast value. If you are constructing
a distribution to test something, make the tail wider than the percentile.

## Warm-up

`warmup` runs the whole workload and throws the measurements away.

The first seconds of any JVM workload measure class loading, JIT compilation,
connection and channel setup and the first garbage collection — none of which the
broker is responsible for. Without a warm-up those costs land in the p99 and stay
there, because a percentile has no way to forget them.

Ten seconds is a reasonable floor. The `run-was-long-enough` rule warns about the
*measured* window separately.

## What is not measured

- **Duplicates.** The message count detects loss and not duplication. The
  sequence numbers are in the payload, so it is possible; it is a different
  measurement and is not implemented.
- **Clock skew across machines.** Latency uses `System.nanoTime()`, so publisher
  and consumer must be in the same JVM. They are, in this tool.
- **A CPU-bound handler.** `handlerTime` is a sleep, which models waiting on a
  database or an HTTP call. A sleeping thread does not compete for a core the way
  a busy one does.
