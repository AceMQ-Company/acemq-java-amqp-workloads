# Patterns under load

`rabbitmq-perf-test` publishes and consumes. It cannot tell you what a retry
ladder costs when three messages in ten fail, how far an outbox relay falls
behind at 2,000 writes a second, or what a claim check adds to a 1 MB message,
because it does not know those things exist. This is what the project is for.

Each pattern here is the library's own code, not a copy of it: the retry ladder
is `ConsumerOptions.withRetry`, the outbox relay is `OutboxRelay`, the claim
check is `ClaimCheckCodec`. A load measures what an application using them gets.

Every pattern is a setting in an ordinary [workload file](workload-file.md), so
everything else still applies: the open-loop schedule, latency from when a
message was *due*, the [rules](rules.md), [ramps](ramp.md), suites for side by
side comparison, and the exit codes.

| Pattern | Where | Example |
|---|---|---|
| Retry ladder | `consumers.retry` | [`18-retry-ladder.yaml`](#a-retry-ladder) |
| Outbox | `publishers.outbox` | [`19-outbox.yaml`](#an-outbox) |
| Claim check | `publishers.claimCheck` | [`20-claim-check.yaml`](#a-claim-check) |

Each pattern adds its own counts to the report, under the totals, in the JSON as
`patterns`, and as metrics a rule can be put on. A run that did not use a
pattern has none of its counts, and a rule on one never fires.

## A retry ladder

```yaml
consumers:
  concurrency: 8
  failureRate: 0.3       # each delivery fails with this probability
  retry:
    attempts: 3          # deliveries in total, the first included
    delay: 500ms         # or initialDelay + maxDelay (+ multiplier) to double
    brokerFrom: 250ms    # waits this long or longer go through a rung queue
    jitter: 0.2          # spread for waits the consumer holds itself
```

`failureRate` is per delivery, so a message fails its first attempt three times
in ten, its second three times in ten again, and is dead-lettered after its
third failure: 2.7% of messages here. A failure goes round on the library's
schedule. A wait below `brokerFrom` is held by the consumer. A wait at or above
it is spent in a `{queue}.retry.{delay}` rung queue, which dead-letters back to
the source queue when the time-to-live expires. The last failure goes to
`{queue}.dlq`.

`brokerFrom` defaults to the library's 30 seconds. A load test runs in seconds,
so set it below the delay to put the rungs under load. Rungs cost the broker
queues and dead-lettering; that cost is what you are measuring.

| Count | Metric | |
|---|---|---|
| retried | `retried` | failed deliveries that went round again |
| recovered | `recovered` | messages delivered on a second or later attempt |
| dead-lettered | `deadLettered` | messages whose last attempt failed |

**Each message counts as consumed once**, when it settles: delivered, or
dead-lettered. A failure that will be retried is not consumed yet. So
`noMessagesLost` still balances confirmed against consumed, and it fails if a
retry went missing on the way through a rung.

**End-to-end latency includes every wait.** It is measured from when the message
was due to the delivery that succeeded. A run where three in ten messages wait
500ms has a p99 of at least 500ms. That is the honest number, and the reason to
run this.

When publishing stops, the run waits for the longest a message can spend on the
ladder (the sum of the waits, plus the jitter) before it drains the consumers. A
ladder with 2 minutes of waits drains for 2 minutes.

From Java:

```java
.consumers(c -> c.concurrency(8).failureRate(0.3)
        .retry(RetryPolicy.fixed(3, Duration.ofMillis(500))
                .waitInBrokerFrom(Duration.ofMillis(250))))
```

## An outbox

```yaml
publishers:
  rate: 2000
  outbox:
    batchSize: 100       # records the relay claims at once
    pollInterval: 50ms   # how long it waits when it found nothing
```

The schedule offers *writes to an outbox* instead of publishes. The library's
`OutboxRelay` claims records in batches and publishes each with a confirm, then
marks it sent. The outbox is in memory, so this loads the relay and the broker,
not a database.

The numbers change meaning, and the report keeps them honest:

- **published** counts writes to the outbox: what the application did;
- **confirmed** counts records the relay got confirmed;
- **publish latency** is from the write to the relay's confirm;
- **end-to-end latency** is from when the write was due to delivery. It includes
  the wait for the relay's next poll, which is the cost of the pattern.

| Count | Metric | |
|---|---|---|
| peak waiting | `outboxPeak` | the most records waiting at once in the window |
| left | `outboxLeft` | records still waiting after a 30 second flush at the end |
| refused | `outboxFull` | writes refused because a million records were waiting; also counted as failed |
| abandoned | `relayGaveUp` | records the relay failed five times; also counted as failed |

The relay publishes one record at a time and waits for each confirm, so its
ceiling is about one round trip per message. A relay that cannot keep up shows
it as a rising `outboxPeak`, then as `outboxLeft` and confirmed below published.
Put a [ramp](ramp.md) over the file to find its ceiling.

The file refuses an outbox with `unthrottled: true` (it would only fill memory),
with `confirms: false` (the relay always confirms), and with `claimCheck` (the
relay writes the record's text as it is, so no codec is involved). Each is exit 3.

The relay publishes text, so an outbox message carries its timestamp as text,
`w<due>:<sequence>:`, padded to `messageSize`.

From Java:

```java
.publishers(p -> p.rate(2_000).outbox(100, Duration.ofMillis(50)))
```

## A claim check

```yaml
publishers:
  rate: 100
  messageSize: 1048576   # 1 MB
  claimCheck:
    threshold: 65536     # bytes at which a body is offloaded; 0 offloads all
    directory: /tmp/acemq-claims   # leave out to keep payloads in memory
```

A body at or above `threshold` goes to a store, and the message carries only its
key. A consumer fetches the body, uses it, and deletes it once the message
settles. A message being retried keeps its body. Below the threshold the body
travels inline, as `ClaimCheckCodec` does, so a threshold above `messageSize`
measures what the framing alone costs.

With `directory` the store is the library's `FilesystemClaimCheckStore`: one
file a payload, a write, a read and a delete beside every publish. Without it the
store is in memory, which measures the broker with small messages. The
difference between the two is the cost of the store.

| Count | Metric | |
|---|---|---|
| claim-checked | `claimChecked` | messages delivered by key and cleared from the store |
| missing | `claimMissing` | deliveries whose key was not in the store |

**The store's write happens on the publishing thread**, inside the publish,
as it does in an application. A slow store shows up as send lag first. If it
gets bad enough, `generator-kept-up` marks the run invalid. That does not mean
the broker failed: the publishing side cannot write that many payloads a
second. Add `threads` until the send lag is gone, and the rate you reach is
what the store sustains. On a busy laptop a 128 KB file write measured 100 to
160 ms, so one thread managed 6 to 9 messages a second.

End-to-end latency includes the store's read. Compare it with a run of the same
file without `claimCheck`, in a [suite](workload-file.md#a-suite), to see what the
pattern costs and what it saves the broker.

From Java:

```java
.publishers(p -> p.rate(100).messageSize(1 << 20)
        .claimCheck(64 * 1024, Path.of("/tmp/acemq-claims")))
```

## Rules on a pattern

The counts are metrics, in the same shape as any other
[rule in the file](rules.md#rules-in-the-file):

```yaml
rules:
  - name: ladder-recovers
    metric: deadLettered
    above: 100
    severity: failed
    because: more than a hundred messages ran out of attempts
  - name: relay-keeps-up
    metric: outboxLeft
    above: 0
    severity: failed
    because: the relay was still behind 30 seconds after the writes stopped
```

## Not yet

Sagas and pipelines are not here yet. A saga needs a compensating flow to fail
into, and a pipeline needs stages to put a handler time on. Both are a scenario's
shape rather than a workload's, and they come next.
