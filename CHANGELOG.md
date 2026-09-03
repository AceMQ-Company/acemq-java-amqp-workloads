# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. It is not tied to
the messaging library's release train.

## [Unreleased]

### Added
- The repository: licence, notice, build, and a README that says what this is
  for and what it is not.
- **`Workload`** — a DSL that keeps topology, load shape and objectives apart,
  because they change independently: the same topology under ten load profiles,
  or the same load against a classic queue and a quorum one.
- **An open-loop rate schedule.** Message *n* is due at `start + n/rate`,
  computed from the start rather than from the previous send, and latency is
  measured from when a message was *due*. A closed loop stalls with the broker,
  stops offering load, and reports latency that improves as the broker gets
  worse. That is coordinated omission, and it is the reason most homemade load
  tools produce numbers that cannot be trusted.
- **`Payload`** carries the intended send time and a sequence number in a
  16-byte header, which is what makes the above measurable.
- **`LatencyRecorder` / `LatencySummary`** over HdrHistogram. Percentiles cannot
  be averaged, so per-thread or per-interval summaries combined afterwards are
  not percentiles of anything; and a list of samples at 300,000/s for five
  minutes is 720MB allocated inside the measurement.
- **Rules, and validity before results.** `Severity.INVALID` outranks `FAILED`:
  a run whose generator never offered its load has not measured the broker, and
  reporting it as the broker missing an objective blames the wrong machine. An
  invalid run never passes.
- **Findings carry evidence, not advice.** Each has an `observation()` with
  numbers in it and an `implication()`. Nothing here says "set prefetch to 250" —
  a tool cannot know your handler's processing time or what else shares the
  broker, and a confident wrong recommendation gets followed.
- **`Objective`** — `throughputAtLeast`, `p99Below`, `percentileBelow`,
  `noMessagesLost`, so a build can fail on "we need 300,000 a second".
- Windowed asynchronous publishing. A synchronous confirmed publish costs a
  network round trip, capping a thread at roughly 550 messages a second against
  a broker 1.8ms away; reaching 300,000 that way would need five hundred
  threads. Measured on the same broker, the same 2,000/s workload went from
  1,048/s achieved with 4.2s of send lag to 2,000/s achieved with 201µs.
- **A command line**: `java -jar acemq-workload.jar -f workload.yaml --report
  reports/ --format html,md,json`. YAML or JSON, single workload or a suite that
  inherits the top level so a classic-versus-quorum comparison does not repeat
  the broker URL and let the two drift apart.
- **Exit codes distinguish the three failure modes**, because a pipeline reads
  the exit code and a person reads the report: `1` for a sound run that missed an
  objective, `2` for a run that measured nothing, `3` for a bad file, `4` for an
  unreachable broker. Retrying an invalid run unchanged gives the same
  non-answer, and a build that cannot tell them apart will do exactly that.
- `${VAR}` and `${VAR:-default}` are read from the environment. A workload file
  is meant to be committed, and a password written literally is a password in
  the git history. `--dry-run` resolves and prints the configuration with the
  password redacted, touching no broker.
- **Unknown settings are refused.** A misspelled `prefech: 500` that was ignored
  would run at the default prefetch and produce a completely normal-looking
  report answering a different question.
- Durations are written as `30s`, `500ms`, `2m`. A bare number is refused: it
  means different things to different readers, and a run of the wrong length
  produces plausible numbers.
- Reports in HTML, Markdown and JSON. **PDF is deliberately not supported** —
  it needs a layout engine and its fonts, and a browser printing the HTML
  produces a better document. The HTML carries `@media print` rules and embeds
  the JSON, so a report that has been mailed to somebody is still re-analysable.
