# Rules and objectives

Every run is checked. Findings come back worst first, so code printing one line
prints the thing that matters.

## Validity outranks results

`Severity.INVALID` sits above `FAILED`, and an invalid run **never passes**
whatever its objectives say.

A run whose generator never offered its load has not measured the broker. It has
no result to judge, and reporting "300,000/s: FAILED" when the client could only
produce 90,000 blames the wrong machine — sending somebody to look at broker
capacity when the problem is the test harness.

| Severity | |
|---|---|
| `INVALID` | the run did not measure what it claims. No conclusion may be drawn |
| `FAILED` | an objective was not met, and the measurement is sound |
| `WARNING` | changes how the result should be read |
| `INFO` | worth knowing, not a problem |

## The rules that always run

### `generator-kept-up` — INVALID

Fires when publishes ran more than 100ms behind their own schedule at p99.

The most important check in the set, and the one homemade load tools do not have.
If message *n* was due at t and went out at t+4s, the offered rate was not the
configured rate, and every throughput figure describes what the client managed.

```
[INVALID] generator-kept-up
    observed:  publishes ran 6220.2ms behind their own schedule at p99 (max 6295.7ms);
               the configured rate was 2,000/s and the achieved rate was 1,048/s
    means:     the configured load was never offered, so this run does not show what
               the broker can take. Raise publisher threads, or run the generator on
               a machine that is not also the broker, and repeat
    detail:    publisher threads=2, send lag p50=4209.0ms
```

Usually the client: too few publisher threads, a `maxInFlight` too small, a
saturated CPU, or a generator sharing a machine with the broker. It can also be
the broker applying back-pressure, which is why the rule reports the observation
rather than naming a cause.

### `broker-not-blocked` — INVALID

Fires when the connection was blocked by a broker resource alarm during the run.

A memory or disk alarm blocks publishing connections. The broker keeps running,
the consumers keep working, the publishers stop — so the run measures how long
the alarm lasted rather than how fast the broker is.

### `publishes-succeeded` — FAILED

Fires when any publish was refused or errored. Different from being slow: a
nacked publish is a message the broker did not take responsibility for.

### `consumers-kept-up` — WARNING

Fires when more than a tenth of what was published was still queued at the end.

Not a failure — a run that deliberately fills a queue is doing this on purpose.
It is a warning because the end-to-end latency of a growing queue is a function
of how long the run lasted: run it twice as long and the p99 doubles, from the
same system.

### `confirms-were-on` — WARNING

Fires when publisher confirms were off. The throughput then counts messages
handed to a socket rather than messages the broker accepted — an upper bound a
durable configuration will not reproduce.

### `tail-is-not-extreme` — WARNING

Fires when p99 is more than ten times the median. Most messages fast, a small
fraction far slower — and a mean or median from that run would describe almost
nobody's experience.

### `run-was-long-enough` — WARNING

Fires when the measured window was under thirty seconds. Short runs measure JIT
compilation, connection setup and the first collection as much as the broker.

## Objectives

```yaml
expect:
  throughputAtLeast: 45000
  p99Below: 50ms
  noMessagesLost: true
```

```java
.expect(Objective.throughputAtLeast(45_000))
.expect(Objective.p99Below(Duration.ofMillis(50)))
.expect(Objective.percentileBelow(99.9, Duration.ofMillis(200)))
.expect(Objective.noMessagesLost())
```

**`throughputAtLeast`** measures what was *consumed* when there are consumers,
not what was published. A publish rate the consumers never matched is a queue
filling up rather than a system running. It reports the shortfall as a
percentage:

```
[FAILED] throughput>=300000
    observed:  sustained 180,000 msg/s end-to-end, against an objective of 300,000
    means:     this configuration delivers 60% of the required rate
    detail:    published=10,800,000 consumed=10,800,000 over 1m0s
```

**`percentileBelow`** is judged on the percentile, never the mean. A run with 980
messages at 1ms and 20 at 200ms has a mean under 5ms and a p99 of 200ms — a tool
reporting the mean would call it healthy.

**`noMessagesLost`** compares confirmed against consumed. It detects loss and
does *not* detect duplication, which needs the sequence numbers and is a
different measurement. When it fires, the queue depth in the report distinguishes
"still draining" from "gone".

## Writing your own

```java
Rule slowStart = report -> {
    if (report.endToEnd().max().toMillis() < 500) {
        return Optional.empty();
    }
    return Optional.of(Finding.of("slow-start", Severity.WARNING,
            "the slowest message took " + report.endToEnd().max().toMillis() + "ms",
            "a single outlier this far from p99.9 is usually a garbage collection"
                    + " or a connection being re-established"));
};

Workload.named("x").rule(slowStart) /* ... */;
```

A rule sees the whole `WorkloadReport` and returns a `Finding` or nothing.
Returning nothing is the normal case — a rule that fires on every run is one
nobody reads, which is the same failure mode as an alert that fires on every
burst.

**Put numbers in the observation.** A finding without a measurement is an
opinion, and the constraint is what keeps this from becoming a tool that guesses.

## Why nothing here recommends a setting

No rule says "increase prefetch to 250".

A tool cannot know your handler's processing time distribution, your message
sizes, your network, your disk, or what else shares the broker. And a confident
recommendation from a tool **gets followed** — which makes a wrong one worse than
saying nothing.

What a tool can say honestly is what it measured and what that rules in or out.
So a `Finding` carries an `observation()` and an `implication()`, and neither is
advice.
