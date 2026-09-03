# Tutorial 2 — Finding the ceiling

**25 minutes.** "We need 300,000 a second — can this broker do it?"

The honest answer requires separating three different limits: the broker's, the
generator's, and the consumers'. Most load-testing mistakes are confusing one for
another.

## Step 1 — Ask for something absurd

```yaml
# ceiling.yaml
name: ask-for-the-moon
broker: amqp://guest:guest@localhost:5672

topology: { queue: tut.ceiling, routingKey: tut.ceiling }

publishers:
  threads: 1
  rate: 300000
  messageSize: 1024

consumers:
  concurrency: 4
  prefetch: 200

warmup: 3s
runFor: 20s

expect:
  throughputAtLeast: 300000
```

```bash
java -jar target/acemq-workload.jar -f ceiling.yaml
```

```
  published     412,033  (20,601/s, offered 300,000/s)

  send lag         n=412033  p50=6.1s   p90=11.2s  p99=13.4s  max=14.1s

  [INVALID] generator-kept-up
      observed:  publishes ran 13.4s behind their own schedule at p99 (max 14.1s);
                 the configured rate was 300,000/s and the achieved rate was 20,601/s
      means:     the configured load was never offered, so this run does not show
                 what the broker can take. Raise publisher threads, or run the
                 generator on a machine that is not also the broker, and repeat
      detail:    publisher threads=1, send lag p50=6.1s

  result: INVALID — this run did not measure what it was asked to
```

```bash
echo $?     # 2
```

**This is the most important result in the tool.** It did not say "the broker
managed 20,601/s and failed". It said *this run tells you nothing about the
broker*, because a single thread could never offer 300,000 messages a second in
the first place.

A tool that reported "300,000/s: FAILED" here would send somebody to look at
broker capacity when the problem is on your side of the socket.

## Step 2 — Give the generator a chance

```yaml
publishers:
  threads: 8
  rate: 300000
  maxInFlight: 5000
```

```
  published     1,204,881  (60,244/s, offered 300,000/s)

  send lag         n=1204881  p50=2.1s   p99=4.8s

  [INVALID] generator-kept-up
      observed:  publishes ran 4.8s behind their own schedule at p99;
                 the configured rate was 300,000/s and the achieved rate was 60,244/s
```

Better — three times the throughput — and still invalid. Keep going until either
the lag collapses or you run out of threads to add.

At some point on a laptop it will stop improving, and that is the answer: **this
machine cannot generate 300,000 a second.** Which is a fact about the machine,
not the broker, and the tool has been careful not to let you mistake it for one.

## Step 3 — Find where the schedule actually holds

Step the rate down until `send lag` collapses. Use a suite so it is one command:

```yaml
# ladder.yaml
broker: amqp://guest:guest@localhost:5672
topology: { queue: tut.ladder, routingKey: tut.ladder }
publishers: { threads: 8, maxInFlight: 5000, messageSize: 1024 }
consumers:  { concurrency: 8, prefetch: 200 }
warmup: 3s
runFor: 20s

workloads:
  - name: rate-10k
    publishers: { threads: 8, maxInFlight: 5000, messageSize: 1024, rate: 10000 }
  - name: rate-20k
    publishers: { threads: 8, maxInFlight: 5000, messageSize: 1024, rate: 20000 }
  - name: rate-40k
    publishers: { threads: 8, maxInFlight: 5000, messageSize: 1024, rate: 40000 }
  - name: rate-80k
    publishers: { threads: 8, maxInFlight: 5000, messageSize: 1024, rate: 80000 }
```

```bash
java -jar target/acemq-workload.jar -f ladder.yaml --report reports/ --format md
```

The Markdown table is the deliverable:

```
| workload | result  | offered  | published | consumed | p50    | p99    |
| rate-10k | PASSED  | 10,000/s | 10,000/s  | 10,000/s | 0.9ms  | 2.1ms  |
| rate-20k | PASSED  | 20,000/s | 20,000/s  | 20,000/s | 1.2ms  | 4.4ms  |
| rate-40k | PASSED  | 40,000/s | 39,980/s  | 39,980/s | 3.1ms  | 28.7ms |
| rate-80k | INVALID | 80,000/s | 52,110/s  | 52,110/s | —      | —      |
```

**The ceiling is between 40k and 80k on this setup**, and the latency at 40k is
already eight times what it is at 10k. That second observation is usually more
useful than the ceiling: the rate at which the system is still *comfortable* is
well below the rate at which it falls over.

The `runFor: 20s` here is short enough that `run-was-long-enough` warns on every
row. For a number you are going to quote, use minutes.

## Step 4 — The unthrottled shortcut, and its cost

```yaml
publishers:
  threads: 8
  unthrottled: true
```

No schedule; publish as fast as possible. It finds the ceiling in one run:

```
  published     1,847,221  (92,361/s)

  [WARNING] unthrottled-latency
      observed:  this run was unthrottled, so there was no schedule to be late against
      means:     the throughput here is meaningful and the latency is not: when the
                 broker stalls, an unthrottled generator stalls with it and stops
                 offering load, which makes the recorded latency better the worse the
                 stall was. Find the ceiling here, then measure latency with rate()
                 set below it
```

**The throughput is real. The latency is not.** Use unthrottled to find the
number, then go back to a fixed rate below it to measure what the experience is
like there.

## Step 5 — Answering the customer

You now have three separate answers, and they are different questions:

1. **"Can it do 300,000?"** Not on this hardware — and this run could not even
   ask, because the generator saturated at about 90,000.
2. **"What can it do?"** About 90,000 unthrottled, with latency that means
   nothing at that point.
3. **"What can it do *well*?"** Around 20,000, where p99 is still low
   single-digit milliseconds.

The third number is the one to build on. The first is the one that gets asked.

To genuinely answer the first: run the generator on separate hardware from the
broker, use several generator machines if one saturates, and expect that 300,000
a second on a single node needs streams rather than classic or quorum queues.

## What you have

- The difference between the broker's ceiling and your generator's
- A rate ladder as a suite, and a table you can hand to somebody
- Why unthrottled measures throughput and destroys latency
- Three different answers to "can it do 300k", and which one to trust

Next: [classic against quorum](tutorial-comparing.md).
