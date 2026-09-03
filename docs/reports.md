# Reports

```bash
java -jar acemq-workload.jar -f workload.yaml --report reports/ --format html,md,json
```

Three formats, defaulting to `html,json`. Files are timestamped, so a series of
runs accumulates rather than overwriting — which is what you want across a week
of tuning.

## The terminal report

```
workload: orders-peak
  TopologySpec{orders -> orders.new [order.created] classic}
  PublisherSpec{threads=4, rate=4000/s, Payload{1024 bytes}, confirms=true}
  ConsumerSpec{concurrency=8, prefetch=100, handlerTime=PT0S}

  window        10s
  published     40,036  (4,000/s, offered 4,000/s)
  consumed      40,036  (4,000/s)
  queue at end  0

  end-to-end       n=40036  p50=1.1ms  p90=1.5ms  p99=4.8ms  p99.9=47.7ms  max=53.2ms
  publish          n=40036  p50=913us  p90=1.3ms  p99=4.4ms  p99.9=47.2ms  max=53.9ms
  send lag         n=40036  p50=188us  p90=225us  p99=322us  p99.9=2.4ms   max=11.4ms

  [INFO] throughput>=3500
      observed:  sustained 4,000 msg/s end-to-end over 10s
      means:     the objective was met

  result: PASSED
```

Read the three histograms in this order:

1. **send lag** — did the generator keep its schedule? If not, stop; nothing
   below is about the broker.
2. **end-to-end** — from *due* to received. The number that matters.
3. **publish** — the confirm round trip. Separates "the broker was slow to
   accept" from "the consumers were slow to drain".

And **`queue at end`**: zero means the consumers kept up. Anything else means the
queue grew and the latency figures are a function of how long you ran.

## JSON

The format nobody asks for and the one that matters most. It is what a pipeline
reads to decide whether to promote a build, what a dashboard graphs over time,
and what lets two runs be compared without re-running either.

```json
{
  "workloads": [
    {
      "name": "orders-peak",
      "startedAt": "2026-09-02T19:59:34.123Z",
      "durationSeconds": 10,
      "valid": true,
      "passed": true,
      "offeredRatePerSecond": 4000,
      "publishedTotal": 40036,
      "consumedTotal": 40036,
      "publishRatePerSecond": 4000.0,
      "consumeRatePerSecond": 4000.0,
      "queueDepthAtEnd": 0,
      "endToEnd": {
        "count": 40036, "p50Micros": 1131, "p99Micros": 4823,
        "p999Micros": 47710, "maxMicros": 53210
      },
      "sendLag": { "count": 40036, "p50Micros": 188, "p99Micros": 322 },
      "findings": [
        { "rule": "throughput>=3500", "severity": "INFO",
          "observation": "sustained 4,000 msg/s end-to-end over 10s",
          "implication": "the objective was met" }
      ]
    }
  ]
}
```

`valid` and `passed` are separate fields for the same reason the exit codes are
separate: a build that cannot tell "missed the objective" from "measured nothing"
will retry the one that can never succeed.

Latencies are in microseconds — integers, so no float comparison surprises when
diffing two runs.

## HTML

Self-contained: no external stylesheet, no CDN, no fonts to fetch. Openable from
a file path, mailable, and viewable on an air-gapped machine.

It carries a comparison table at the top, then each workload's findings and full
report, and **embeds the JSON** in a collapsed block at the bottom — so a report
forwarded to somebody is still re-analysable rather than a picture of numbers.

It respects the reader's light or dark theme, and has `@media print` rules.

## Markdown

For a pull request comment or a wiki page. Leads with the comparison table:

```markdown
| workload | result | offered | published | consumed | p50 | p99 | p99.9 |
|---|---|---|---|---|---|---|---|
| classic-queue | PASSED | 4,000/s | 4,000/s | 4,000/s | 1.4ms | 2.2ms | 5.3ms |
| quorum-queue | PASSED | 4,000/s | 4,000/s | 4,000/s | 1.1ms | 4.8ms | 47.7ms |
```

That single table is often the whole deliverable: same load, two configurations,
and the cost of the guarantee in a number.

## PDF

Not supported, deliberately. `--format pdf` says so rather than failing quietly.

Producing one needs a layout engine and its fonts — a large dependency in a tool
whose entire output is a table and a list — and the result looks worse than what
a browser prints. Open the HTML and print it: the `@media print` rules drop the
embedded JSON block, avoid breaking findings across pages, and switch to a
palette that survives a monochrome printer.

If a signed, archival PDF is a hard requirement for a customer deliverable, that
is a wrapper around the HTML rather than a feature of this tool.

## Comparing two runs

Nothing in this tool does it for you yet. With the JSON it is a few lines:

```bash
jq -r '.workloads[] | "\(.name)\t\(.consumeRatePerSecond)\t\(.endToEnd.p99Micros)"' \
  reports/workload-*.json | column -t
```

A per-run comparison mode is on the roadmap. Until then the JSON is stable enough
to build on, which is most of why it is written by default.
