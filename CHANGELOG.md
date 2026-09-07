# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. It is not tied to
the messaging library's release train.

## 0.1.3 — 2026-09-06

### Added
- **The command line runs a scenario file.** `-f` takes either kind; which one it
  is, is decided by what is in the file rather than by a flag somebody has to
  remember. Until now the studio could export a scenario that nothing but the
  studio could run, which made the whole pipeline story aspirational.
- **Objectives per node.** `expect` on a queue — `p50Below`, `p99Below`,
  `p999Below`, `consumeRateAtLeast`, `noBacklog` — and on a producer —
  `achievedRateAtLeast`, `withinPercentOfOffered`, `noFailures`. A missed one is
  a `FAILED` finding naming the node and both numbers, and the process exits `1`,
  so a scenario can fail a build on a number. Per node rather than for the whole
  run: the interesting property is usually asymmetric, and an overall p99 averages
  away exactly the distinction worth keeping. A queue that received nothing fails
  a latency expectation rather than passing it, because unanswerable is not the
  same as met.
- **Scenario reports.** `--report` writes `html`, `md` and `json` for a scenario
  as well as a workload, with a table of queues and a table of producers. Each
  node carries what it was asked for alongside what it did.
- **The studio opens a scenario file**, JSON or YAML, with its problems reported
  alongside it rather than instead of it. `${VAR}` is deliberately **not**
  resolved on this path: doing so would read the studio's own environment on
  behalf of whoever opened the file and hand the value back.
- **A *What it must prove* panel** on every queue and producer in the designer,
  so objectives are set where the node is configured and travel in the exported
  file.
- **`--broker <url>`** overrides the broker in the file, for running the same
  scenario against staging and then production.
- **The report as a file, from the studio.** A finished run offers HTML, Markdown
  and JSON, written by the library's own writers so it is the same document the
  command line produces for the same run. A run watched here and then described
  from memory in a ticket is a run nobody else can check.
- **Presets ship with their objectives set**, so the gate is something somebody
  meets rather than something they read about. The comparison presets assert that
  both legs kept up and that the generator offered its load; the slow-consumer
  preset asserts it of the fast leg only, because the slow one is there to fall
  behind. `find-the-ceiling` deliberately asserts nothing: it is unthrottled, and
  its latency means nothing.
- **The studio's own integration test.** Start a run through the API against a
  real broker, watch readings arrive, get a verdict, find it in the history and
  take the report away as a file. Pressing Run is the product, and until now a
  broken Run button could have been released without anything noticing.
- [Scenario file](docs/scenario-file.md) documents the format, the objectives and
  what is deliberately not checked, and [tutorial 4](docs/tutorial-ci-gate.md)
  now gates a whole topology rather than one path.

### Fixed
- **The command line jar could not name its own version.** `--version` answered
  "acemq-workload (from source)" in 0.1.0, 0.1.1 and 0.1.2: the shaded jar's
  manifest carried a main class and nothing else, so the version somebody quotes
  in a bug report was never the version they were running. Found by the new
  release preflight, on the release it was about to sign off.
- **A stream was reported as falling behind.** Consumers move an offset and
  nothing is removed, so a stream's depth is the length of its log rather than a
  backlog. Reporting it as one described a stream that kept up perfectly as one
  whose consumers were losing ground — exactly backwards. Depth now reads
  "retained" for a stream, and `noBacklog` is not checked for one.

### Changed
- **The studio keeps each finished run's report as HTML and Markdown** as well as
  JSON. Two nullable columns, added to an existing database on start, because
  the renderer needs the report object and that exists only while the run is in
  memory.
- `ScenarioJson` became `org.acemq.workloads.scenario.ScenarioFile` and moved from
  the studio into the library, because the file format is the contract between the
  designer and the command line and cannot live in only one of them.

## 0.1.2 — 2026-09-06

### Added
- **TLS and mutual TLS in the studio.** An `amqps://` URL turns on a TLS section
  on the connection screen: a certificate authority, a client certificate and
  key for mutual TLS, and two switches that are named for what they do —
  accepting development certificates, and trusting anything.
- **A real handshake, not a TCP connect.** Port 5671 answers a socket whether or
  not the certificate on it is acceptable, so the studio completes a handshake
  and reports the protocol, whether the chain verified, what the broker
  presented, when it expires, whether it is stamped development-only, and
  whether the broker asked for a client certificate in return.
- **PEM in, keystores out.** `Security.fromKeystore` wants two PKCS#12 files and
  nobody has those; what a broker hands out is `ca.pem`, `client.crt` and
  `client.key`. The studio reads those — PKCS#8 and PKCS#1 keys alike — and
  writes the stores itself, into a directory only its user can read. An
  encrypted key is refused with the command that decrypts one, because holding
  a passphrase would make the studio the thing that leaked it.
- **`ScenarioRunner.run` and `start` take a `Security`**, so a scenario can be
  run against a broker that needs TLS from the library as well as the studio.

### Fixed
- **The published pom declared only its test dependencies**, in 0.1.0 and 0.1.1.
  The shade plugin writes a "dependency-reduced" pom on the assumption that
  whatever it bundled no longer needs declaring — but this build shades into a
  separately named CLI jar and still publishes the ordinary thin jar as the
  artifact people depend on. Anybody who resolved
  `org.acemq:acemq-java-amqp-workloads` from Maven got a jar with no
  `acemq-amqp-core` behind it and a `NoClassDefFoundError` the first time they
  called anything. The CLI jar attached to those releases was always complete;
  only the Maven artifact was affected.

### Changed
- A failed run reports its root cause as well as its message. "could not connect
  to amqps://broker:5671" is what the transport says whether the certificate was
  refused, the password was wrong or the port was closed, and the answer is
  three causes further down.

## 0.1.1 — 2026-09-06

### Added
- **The studio.** `java -jar acemq-workloads-studio.jar` opens a browser
  interface for designing a topology, running load against it and watching what
  happens: a canvas, ten presets, a live view, run history in a SQLite file, and
  an export that is the same file the command line reads — so a scenario
  designed on a screen runs unchanged in a pipeline.
- **`Scenario`** — a whole topology rather than one path: several exchanges,
  several queues each with their own consumers, and producers aimed at chosen
  keys. Every node can be switched off without being deleted, because "what
  happens if this consumer stops" is the question people run these things to
  answer. Everything is counted per node: a single pair of totals cannot
  describe a graph, and total throughput looks healthy while one leg of a
  fan-out falls behind.
- **Live readings.** `Workload.start` and `ScenarioRunner.start` take a listener
  and return a handle, reporting about once a second on a thread of their own so
  the publishers pay nothing for it. Rates are per-interval rather than
  averages, because an average cannot show a stall.
- **Stopping a run.** `stop()` ends the measured window early and still reports
  on what it measured, marked as stopped so nobody reads a twenty-second window
  as the two minutes that were asked for.
- **Stream and mirrored-classic queue types**, and `BrokerCapabilities`, which
  asks a broker which types it will honour. Mirrored classic queues were removed
  in RabbitMQ 4.0 and a 4.x broker accepts the policy and ignores it, so
  offering the option unconditionally would measure a classic queue under
  another name.
- **A Dockerfile and a compose file.** Non-root, heap sized from the container's
  limit, an open liveness endpoint, and graceful shutdown that stops a running
  scenario on SIGTERM and keeps its report.
- **A connection resolver.** Inside a container `localhost` is the container, so
  the studio tries `host.docker.internal`, `host.containers.internal` and the
  default gateway, and says which one answered rather than rewriting silently.

### Changed
- The repository is two Maven modules, `library/` and `studio/`. The published
  coordinates are unchanged — `org.acemq:acemq-java-amqp-workloads` — and the
  studio is distributed as a release asset rather than a Maven artifact, because
  nobody declares an application as a dependency.

## 0.1.0 — 2026-09-03

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
