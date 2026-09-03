# The workload file

YAML or JSON, chosen by the file extension. YAML is the better choice for
anything a person maintains, because a load test's configuration wants a comment
explaining why the rate is what it is, and JSON has none.

```yaml
# Peak hour is 38,000/s measured over the last quarter.
# 50,000 gives headroom for a bad Monday.
name: orders-peak
broker: amqp://guest:${BROKER_PASSWORD}@localhost:5672
management: http://localhost:15672

topology:
  exchange: orders
  exchangeType: topic
  queue: orders.new
  routingKey: order.created
  queueType: classic
  arguments:
    x-message-ttl: 60000

publishers:
  threads: 4
  rate: 50000
  messageSize: 1024
  confirms: true
  maxInFlight: 1000

consumers:
  concurrency: 8
  prefetch: 100
  handlerTime: 1ms

warmup: 10s
runFor: 2m

expect:
  throughputAtLeast: 45000
  p99Below: 50ms
  noMessagesLost: true
```

## Top level

| Setting | | |
|---|---|---|
| `name` | required | identifies the run in the report |
| `broker` | required | AMQP URL |
| `management` | | management URL, for the queue depth at the end |
| `managementUser` / `managementPassword` | | default `guest` |
| `warmup` | `10s` | run this long before measuring anything |
| `runFor` | `60s` | the measured window |

## `topology`

| Setting | | |
|---|---|---|
| `exchange` | `""` | the default exchange routes by queue name and skips exchange routing — the fastest path, and not what most systems do |
| `exchangeType` | `topic` | `topic`, `direct`, `fanout`, `headers` |
| `queue` | `acemq.workload` | |
| `routingKey` | | the publish key, and the binding key |
| `queueType` | `classic` | or `quorum` |
| `arguments` | | queue arguments |
| `declare` | `true` | set `false` to use the topology as it already exists |

`declare: false` is the right choice when measuring a real environment.
Declaring would either be refused for mismatched arguments or, worse, create
something subtly different from what production runs and measure *that*.

## `publishers`

| Setting | | |
|---|---|---|
| `threads` | `1` | how many threads share the offered rate |
| `rate` | | **offered** messages per second, across all threads |
| `unthrottled` | `false` | publish as fast as possible — see below |
| `messageSize` | `1024` | bytes, including a 16-byte header |
| `randomPayload` | `false` | random bytes rather than zeroes |
| `confirms` | `true` | wait for the broker to confirm |
| `maxInFlight` | `1000` | unconfirmed publishes a thread may hold |
| `maxMessages` | | stop after this many |

**`threads` and `rate` are separate on purpose.** "Ten producers" is not a load.
The rate is the property of the experiment; the threads are how much parallelism
the client uses to keep up with it.

**`maxInFlight` is the setting that decides whether you can generate a serious
rate.** A publish that waits for its own confirm costs a network round trip, so a
thread doing that is capped at about 550 messages a second against a broker 1.8ms
away — whatever the broker can take. Measured on the same broker:

| offered 2,000/s | one at a time | windowed |
|---|---|---|
| achieved | 1,048/s | **2,000/s** |
| send lag p50 | 4,209ms | **201µs** |

**`unthrottled: true` measures throughput and invalidates latency.** With no
schedule there is no "due" time, so a broker that stalls also stalls the
generator, and the recorded latency improves as the stall worsens. Use it to find
the ceiling, then set `rate` below the ceiling and measure latency there. The
report says so when you do.

**`confirms: false` changes what the throughput means.** A publish without
confirms is a message handed to a socket, not one the broker accepted. The rate
goes up and some of those messages were never durably anywhere — not a number to
promise a customer.

## `consumers`

| Setting | | |
|---|---|---|
| `concurrency` | `1` | how many consumers. `0` for a publish-only run |
| `prefetch` | `100` | unacknowledged messages a consumer holds. `0` is unlimited |
| `handlerTime` | `0s` | simulated work per message |
| `failureRate` | `0` | fraction of messages the handler rejects |

**`handlerTime` is the setting people leave out and then wonder why the results
do not resemble production.** A consumer that does nothing measures the broker; a
consumer that takes two milliseconds measures the broker *and* what happens when
the application is the slow part — which is where almost every real system lives.

It is implemented as a sleep, which models waiting on a database or an HTTP call.
It does not model a CPU-bound handler, because a sleeping thread does not compete
for a core the way a busy one does.

## `expect`

| Setting | |
|---|---|
| `throughputAtLeast` | messages per second, measured **end to end** when there are consumers |
| `p50Below` / `p99Below` / `p999Below` | a latency budget |
| `noMessagesLost` | every confirmed message must arrive |

`throughputAtLeast` counts what was *consumed*, not what was published. A publish
rate the consumers never matched is a queue filling up rather than a system
running.

## Durations

`500ms`, `30s`, `5m`, `2h`, `250us`, `100ns`.

A bare number is refused. `runFor: 60` is a minute to one reader and a second to
another, and a run of the wrong length produces numbers that look entirely
plausible.

## Environment variables

`${VAR}` and `${VAR:-default}` are substituted before parsing.

An unset variable with no default is an **error**, not an empty string. A
workload file is meant to be committed and reviewed; a literal password is a
password in the git history.

## Unknown settings are refused

```
unknown setting 'consumers.prefech'. Known settings here: concurrency,
prefetch, handlerTime, failureRate. A misspelled setting that was ignored would
run with the default and report a result that looks entirely normal.
```

That is the whole argument. A typo that is silently ignored produces a full
report for a configuration nobody chose.

## A suite

Several workloads in one file. Each entry inherits the top level, so the shared
parts are written once and cannot drift apart:

```yaml
broker: amqp://guest:guest@localhost:5672
publishers: { threads: 4, rate: 20000, messageSize: 1024 }
consumers:  { concurrency: 8, prefetch: 100 }
warmup: 10s
runFor: 1m

workloads:
  - name: classic-queue
    topology: { exchange: bench, queue: bench.classic, routingKey: k, queueType: classic }
    expect: { p99Below: 20ms }

  - name: quorum-queue
    topology: { exchange: bench, queue: bench.quorum, routingKey: k, queueType: quorum }
    expect: { p99Below: 20ms }
```

They run in order against the same broker and are reported side by side. This is
how to answer "what does a quorum queue cost us" with a number rather than an
opinion — and why the shared settings are inherited rather than copied, since two
copies that disagree by one field make the comparison meaningless.
