# The ramp

"Can this broker do 300,000 a second?" has an honest answer only at a rate
where every objective still holds. A ramp finds that rate: it steps the offered
rate up until a rule fires, and reports the last step where nothing did.

```bash
java -jar acemq-workload.jar -f examples/17-ramp.yaml --report reports/ --format html,md,json
```

```
ramp: ramp-to-ceiling
  Ramp{ramp-to-ceiling from 2000/s by 25.0% to 200000/s, 20s a step, refined 2x}

    offered      result       published     consumed       p50       p90       p99     p99.9  stopped by
    2,000/s      ok             2,000/s      2,000/s     1.1ms     1.6ms     2.4ms     4.0ms
    2,500/s      ok             2,500/s      2,500/s     1.2ms     1.8ms     3.0ms     5.1ms
    ...
    9,538/s      ok             9,538/s      9,538/s     2.9ms     7.7ms    21.4ms    38.0ms
    11,923/s     FAILED        11,923/s     11,104/s   212.6ms   402.3ms   470.9ms   488.2ms  p99<50.0ms, kept-pace
  * 10,730/s     FAILED        10,730/s     10,511/s    41.0ms    88.1ms    96.4ms   101.3ms  p99<50.0ms
  * 10,134/s     ok            10,134/s     10,134/s     3.4ms    11.2ms    33.0ms    44.7ms
  * refinement step, between the last good rate and the first bad one

  highest sustained rate: 10,134/s
  stopped by: p99<50.0ms, kept-pace at 11,923/s: p99 was 470.9ms, against a budget of 50.0ms
  objective: ceiling at least 5,000/s
  result: PASSED
```

*(Illustrative: a laptop, broker and generator sharing a CPU.)*

The p99 column is the other half of the answer. The rate at which the system is
still *comfortable* is usually well below the one where it falls over, and the
ramp shows how latency bent on the way up.

## Every step is an ordinary run

A step is the workload run at one fixed rate through the same open-loop engine
as any other run: its own warm-up, then a measured window, with latency taken
from when each message was **due** rather than when it went out. A broker that
stalls shows the stall as latency; it cannot slow the generator down with it
and report a flattering number. See [measurement](measurement.md) for the
arithmetic.

So a ramp is not a second engine with a moving rate. It is a loop over runs,
and every step is judged by exactly the [rules](rules.md) a single run is.

## What stops it

The first bad step ends the climb. A step is bad when:

| | |
|---|---|
| an objective is missed | anything under `expect:` — `p99Below: 50ms` at that rate |
| a rule fires | any rule, default or [loaded from the file](rules.md#rules-in-the-file), with severity `failed` or `invalid` |
| `kept-pace` | added to every step: what was delivered fell more than `tolerance` (5%) short of what was offered |
| failed publishes | any, unless under `maxErrorRate` |
| a step is **invalid** | the generator could not keep its schedule, or the broker raised an alarm |

Warnings never stop it: a 20-second step warns that it was short on every step.

**`kept-pace` reads the one-second samples, not the totals.** A run's totals
include the drain after the window, and five seconds of drain lets a slow
consumer catch up on a step's backlog and look as if it kept pace. Without
this rule a slow consumer would never stop a ramp at all, because a filling
queue is only a warning on a single run.

**An invalid step means the ceiling is not the broker's.** If the generator
fell behind its schedule at 60,000/s, the ramp found the generator's limit —
and says so, rather than reporting 55,000/s as the broker's ceiling. Raise
publisher threads or run the generator elsewhere, and ramp again.

`maxRate` reached with everything holding ends it too, and the report says the
ceiling is *at least* that rather than inventing a number past it.

## Refinement

`refine: N` bisects N times between the last good step and the first bad one,
so a coarse step does not leave the answer that coarse. Refinement steps are
marked `*` and listed after the climb.

A refinement step that follows an overloaded one inherits whatever backlog it
left in the queue. That can only make the refinement step look worse, never
better: a refined ceiling can be low, never high.

## In a workload file

```yaml
ramp:
  start: 2000          # the first step, msg/s. Default: publishers.rate
  step: 25%            # each step adds 25%, or an absolute rate: step: 1000
  stepFor: 20s         # each step's measured window. Default: runFor
  warmup: 5s           # before each step's window. Default: warmup
  maxRate: 200000      # never offer more than this
  maxErrorRate: 0.1%   # failed publishes allowed per step. Default 0
  tolerance: 5%        # delivered may fall this far short of offered. Default 5%
  refine: 2            # bisection steps after the climb. Default 0
  ceilingAtLeast: 5000 # the gate: exit 1 if the ceiling is lower
```

Only `step` is required, given a rate on the publishers. Shares are written
`5%` or `0.05`. A step must be at least 5 seconds: `kept-pace` averages
one-second readings and drops the first, which straddles the counter reset.

The ramp sets the publishers' rate on every step, so a rate in the file is only
the default start, and `unthrottled` is replaced. Everything else — topology,
consumers, objectives, rules — is the same on every step.

In a [suite](workload-file.md#a-suite), `ramp:` at the top level ramps every
entry, and an entry can carry its own. As with any block, an entry's `ramp:`
replaces the top level's rather than merging into it.

## On the command line

`--ramp` is the `ramp:` block written on one line, for ramping a committed
workload file without editing it:

```bash
java -jar acemq-workload.jar -f workload.yaml \
  --ramp start=1000,step=10%,stepFor=20s,maxRate=50000,refine=3
```

It goes through the same parser as the file, so the two cannot disagree. Where
the file already has a ramp, the flag wins key by key. Every report records the
settings the ramp ran with, so the run is still reproducible from the report.
Scenario files are refused: a scenario has many producers, and which of their
rates a ramp would raise is not something to guess.

## From Java

```java
RampReport ramp = Ramp.of(workload)
        .startAt(2_000)
        .stepByPercent(25)              // or .stepBy(1_000)
        .stepFor(Duration.ofSeconds(20))
        .warmupEachStep(Duration.ofSeconds(5))
        .maxRate(200_000)
        .refine(2)
        .run("amqp://guest:guest@localhost:5672");

ramp.highestSustainedRate();   // 10_134
ramp.stoppedBy();              // "p99<50.0ms, kept-pace"
ramp.isValid();                // false when an invalid step ended it
ramp.steps();                  // each with its rate and its WorkloadReport
```

`ramp.stop()` ends it after the step running.

## Exit codes

| | |
|---|---|
| `0` | the ceiling met `ceilingAtLeast`; or, with none set, the ramp was valid and sustained its first step |
| `1` | a sound ramp's ceiling fell short of `ceilingAtLeast`, or the start rate already missed |
| `2` | an **invalid** step ended it and the gate was not met — the ceiling found is the generator's or an alarm's |
| `3` | the `ramp:` block is wrong |
| `4` | the broker could not be reached |

A ramp that met `ceilingAtLeast` passes however it ended: a generator that
gave out at 60,000/s has still shown the broker takes 50,000.

## Reports

The terminal shows the table above. The **HTML** report draws the curve —
delivered against offered, with the diagonal a perfect system would follow,
and p99 against offered — with failed steps hollow, then the step table and the
finding that stopped it. **Markdown** has the table and the p99 curve as a text
bar chart. **JSON** carries a `ramps` array beside `workloads`:

```json
"ramps": [
  {
    "name": "ramp-to-ceiling",
    "settings": "Ramp{ramp-to-ceiling from 2000/s by 25.0% to 200000/s, 20s a step, refined 2x}",
    "valid": true,
    "passed": true,
    "verdict": "PASSED",
    "highestSustainedRatePerSecond": 10134,
    "ceilingAtLeast": 5000,
    "stoppedBy": "p99<50.0ms, kept-pace",
    "stopReason": "p99<50.0ms, kept-pace at 11,923/s: p99 was 470.9ms, against a budget of 50.0ms",
    "steps": [
      {"offeredRatePerSecond": 2000, "refinement": false, "sustained": true, "valid": true,
       "publishRatePerSecond": 2000.0, "consumeRatePerSecond": 2000.0, "failedTotal": 0,
       "endToEnd": {"p50Micros": 1100, "p90Micros": 1600, "p99Micros": 2400, "p999Micros": 4000, "maxMicros": 6100}}
    ]
  }
]
```

`ramps` is absent when nothing ramped, so a pipeline reading `workloads` sees
exactly what it saw before.

## What it is not

Not a soak, and not a distributed load: one generator, one workload, rates in
a straight climb. A ceiling measured on a laptop is the laptop's.
