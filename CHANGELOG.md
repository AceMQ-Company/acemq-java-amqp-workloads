# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. It is not tied to
the messaging library's release train.

## [Unreleased]

### Added
- **An examples library**, fourteen examples in [`examples/`](examples) ordered
  simple to complex, and [a page that walks them](docs/examples.md). The first
  is a queue, a rate and somebody to drain it; the last is a three-node cluster
  losing a node mid-run. In between: objectives, all five exit codes, classic
  against quorum, a stream, fanout, topic routing, a consumer that cannot keep
  up, an unthrottled ceiling, a run that measures nothing, a CI gate and a
  dead-letter path. Every one carries comments explaining what it demonstrates
  and what to read in the output, and every one was run before it shipped — the
  page marks which output is real.
- **The studio guide is part of the site, with a picture of every screen it
  describes.** `studio/USAGE.md` moved to [`docs/studio-guide.md`](docs/studio-guide.md),
  which is what the published site renders, and its six numbered sections now
  show the screen each one is about: the connect screen, the canvas, the
  inspector, the objectives panel, a run in progress, the verdict, the run
  history and a two-run comparison. Every picture is a capture of the running
  studio, taken by [`scripts/screenshots.sh`](scripts/screenshots.sh) driving it
  through that walkthrough against a real broker — the same preset, the same
  rates, a fixed viewport, so a re-run produces a diff rather than noise. Each
  shot is preceded by an assertion that the screen still says what the guide
  claims, so the walkthrough cannot drift away from the build unnoticed.
  `studio/USAGE.md` stays as a pointer for anybody reading the repository.

### Changed
- **`examples/comparison.yaml` is now `examples/05-queue-types.yaml`.** The same
  question — what does a quorum queue cost us — answered on a queue that is
  actually a quorum queue. The old file was a workload suite, and at the time a
  workload file's `queueType` did not reach the broker, so what it shipped was
  classic against classic, passing. The replacement is a scenario: one fanout
  exchange, both queues fed the same messages in the same second, and a
  four-times gap in p99 that the old file could not have shown. The underlying
  bug is fixed below, so a suite would work now too; the scenario stays, because
  feeding both queues the same message in the same instant is the better
  experiment and not merely the available one.

### Fixed
- **A workload file's `topology.queueType` and `topology.arguments` never
  reached the broker.** Both were parsed, both were validated, and both were
  then left behind when the workload was translated into the scenario the engine
  runs: the queue node was built with its bindings and its consumers and nothing
  else, so every workload file declared a classic queue whatever it said. What
  made this expensive rather than merely wrong is that nothing looked broken.
  The report prints the type from the spec, so it read back `quorum` for a queue
  the broker had made classic, and two workloads differing only in `queueType`
  agreed with each other and passed. A measured comparison came out at p99 2.4ms
  against 38.3ms once both queues were really what they claimed.
- **`publishers: confirms: false` was a no-op.** The value reached the producer
  node and stopped there; the engine opened its connection without ever asking
  what the producer wanted, and publisher confirms are a property of the
  connection in this transport. Two runs differing only in this field measured
  643µs and 662µs publish p50 — the same run twice. They now measure 777µs and
  23µs, which is the round trip the setting is about. A scenario whose producers
  disagree about confirms is refused rather than resolved silently, since one
  connection cannot negotiate both answers.
- **`publishers: randomPayload: true` did nothing unless `messageSize` was also
  written.** It was read inside the branch that handles the size, so on its own
  it was dropped and the run went out with a kilobyte of zeroes — which is the
  payload anything that compresses is best at, and therefore the one that
  flatters a broker most. The two keys are now one setting between them and
  either may be written alone.
- **`topology.queueType: stream` was silently downgraded to classic**, so a file
  asking for an append-only log got a queue that deletes what it delivers. A
  workload is one queue and one path, and a stream is not that shape: it is
  refused now, pointing at the scenario file that can express one.
- **`topology.exchangeType` with no `topology.exchange` was accepted and
  ignored.** With no exchange the publish goes through the default one, which
  routes by queue name and has no type, so `fanout` and `direct to the queue`
  produced identical runs from files that read differently. Refused.
- **The command line printed the broker password when a run failed.** A run
  against an unreachable broker answered with `could not connect to
  amqp://guest:hunter2@localhost:5672`, one line below a banner that had
  redacted the identical URL — the redaction existed and that path did not call
  it. This is a CI log, which is archived and frequently public. Redaction now
  happens on the way out of the stream rather than at whichever call sites
  somebody remembered, so a URL cannot reach a terminal or a log unredacted by
  arriving along a route nobody thought of. Four routes were leaking, all of
  them a message written somewhere below us and passed through: the failed-run
  line on the workload path and on the scenario path, and in the studio the
  stored failure text — which goes into the run history, out to the browser over
  the event stream, and on to whoever the history file gets copied to — and the
  server log line beside it. The studio redacted the broker column somebody
  thought of and stored the message whole, which is the same fault in the same
  shape.
- **A refused declaration exited 4, "the broker could not be reached".**
  Redeclaring an exchange under a different type is refused by a broker that
  answered perfectly well, and calling that unreachable sent the reader to
  check firewall rules and hostnames for a mistake in their own file. A refusal
  — anything the broker answers with a reply code, so credentials it will not
  take and a missing queue as well as an inequivalent redeclaration — is now
  exit 3, the code a misspelled setting gets, because it is the same kind of
  problem and is fixed in the same place. Exit 4 keeps its meaning: nobody
  answered. What the broker said is printed alongside, since it is the only
  sentence that names the setting to change.
- **Three SLF4J warnings printed before every run.** `slf4j-api` with no
  provider on the class path announces its absence in three lines, which were
  the first thing a new reader of the getting-started page saw and which appear
  in none of the transcripts in `docs/`. The jar now carries the no-operation
  provider explicitly — the one slf4j was already falling back to, so nothing
  about what is logged has changed. It is declared `optional`, so nobody
  depending on the library has a logging decision made for them.
- **`${VAR}` written inside a `#` comment stopped a scenario run.**
  Substitution runs over the whole file before it is parsed, which is what lets
  a placeholder stand anywhere rather than only in the fields somebody
  anticipated — but it also ran over the comments, so a file trying to explain
  its own syntax to the next reader aborted with an unset-variable error over a
  line the parser never sees. A comment is not part of the document.
- **The management API was never actually asked.** The studio's probe sent no
  credentials, so on a broker that wants a user — which is every broker — the
  management query was refused and the connect screen reported that the
  management API did not answer about an API that had answered perfectly well.
  What that cost was visible: stream and mirrored-classic queues greyed out in
  the designer, and **Import from broker** unavailable, on brokers that support
  both. It now sends the user written into the AMQP URL, which is the same user
  in every case anybody points this at.
- **The documented path to the jar was wrong.** `mvn -DskipTests package`
  produces `library/target/acemq-workload.jar`, not `target/acemq-workload.jar`
  — the build has had two modules since the studio arrived. Every command in
  the guide and the five tutorials said the latter, so the first thing a new
  reader typed failed.
- **Suite inheritance replaces a block rather than merging into it**, and
  nothing said so. An entry writing `publishers: { confirms: false }` silently
  reverts `threads`, `rate` and `messageSize` to their defaults and reports a
  perfectly normal-looking result for a configuration nobody chose. Documented
  in [the workload file](docs/workload-file.md#a-suite).
- **A scenario file has no `management` setting**, and the page now says so.
  Depth is read over AMQP during a scenario, because ten queues would otherwise
  mean ten HTTP requests a second to the broker being measured.
- **A workload file's `topology.queueType` and `topology.arguments` never reach
  the broker**, and the page said they did. The queue is declared classic
  whatever `queueType` says, while the report prints back the type that was
  asked for — so a suite comparing `classic` with `quorum` compares classic with
  classic and passes. A scenario's `type` and `arguments` are honoured, which is
  why every queue-type example is a scenario.
- **`publishers.confirms: false` is not honoured by a run**, and the page said
  it was. The value reaches the report and raises the `confirms-were-on`
  warning; the engine publishes with confirms either way, so two runs differing
  only in that field come back with the same publish latency and the same rate.
  Marked as an intention rather than a configuration until it is connected.
- **`noMessagesLost` has no tolerance at the run boundary**, which the page now
  says. A confirm landing inside the measured window whose delivery is counted
  just after it reads exactly like loss, so a healthy run at 5,000/s reports two
  or three messages unaccounted for beside a queue depth of zero about half the
  time. The finding's own detail line separates the two cases.

## 0.1.4 — 2026-09-07

### Added
- **The interface has tests.** Two layers: component tests in jsdom that run
  inside `mvn test` on every JDK, and end-to-end tests in headless Chromium
  against the real jar and a real broker, in their own CI job with a RabbitMQ
  service container. Half the studio is TypeScript and none of it was covered by
  anything; every interface bug this project has had was found by somebody
  clicking, and each end-to-end test is one of those bugs. `./scripts/e2e.sh`
  runs the browser layer by hand, starting a broker in Docker if it is not given
  one.
- **Two runs, side by side.** Tick two in the history and press Compare. Every
  measurement they have in common, with the direction made explicit: a latency
  that went up is worse, a rate that went up is better, and the table says which
  rather than leaving the reader to work it out from the sign at the moment they
  are least likely to. Anything inside 5% is reported as the same run, because
  two runs of one configuration differ by a few percent on ordinary hardware.
  This is the reason every reading was being kept, and until now nothing could
  read them back.
- **Runs can be deleted, and old ones are dropped.** The 200 most recent are
  kept — `acemq.studio.keep-runs` — and the rest go as new runs finish. A studio
  left open takes a reading a second for every run it has ever made, which is
  what makes a finished run drawable again and also what made the file grow
  without limit.
- **Bindings can be edited and removed** in the designer, and queue arguments
  can be set: `x-max-length`, `x-message-ttl`, `x-max-age` on a stream. Dragging
  on the canvas made a binding and nothing could change one afterwards, so a
  wrong routing key meant deleting the queue and drawing it again.
- **The studio's image is published** to `ghcr.io/acemq-company/acemq-workloads-studio`
  on every version tag, for amd64 and arm64, and the workflow refuses to finish
  until the image it pushed has started and served its interface. The Dockerfile
  had been in the repository since the studio existed with nothing publishing
  what it describes.
- **[studio/USAGE.md](studio/USAGE.md)**, every screen in the order somebody
  meets it, and [a studio page](docs/studio.md) on the documentation site, which
  had fifteen pages and none about the thing with the interface.
- `ProducerNode.payload(Payload)`, so a scenario can use random message bodies.
  Identical bodies can be compressed or deduplicated somewhere in the path and
  flatter the result.
- `ScenarioRunner.run` taking a listener and a stop flag: a synchronous run with
  live readings, which is what a command line printing progress wants.

### Fixed
- **Labels in the designer were not attached to their fields.** They sat above
  the box and said nothing to anything that was not a pair of eyes: a screen
  reader announced an unlabelled text box, and nothing could find a field by the
  name printed beside it. Found by the end-to-end tests, which could not fill in
  "Warm-up".

### Changed
- **One measurement engine.** `WorkloadRun` and `ScenarioRun` were 487 and 544
  lines of the same thing — an open-loop publisher schedule, a sampler, a block
  watcher, a drain. A workload is a scenario with one node of each kind, so the
  workload path is now the translation either side of the scenario engine, and
  the second copy is gone. Two copies of a measurement engine is two places for
  the measurement to be subtly wrong and only one of them gets fixed; this
  project was caught by exactly that in the Go library.
- The README led with the Java DSL and said `0.1.0` with a test count three
  releases old. It leads with the studio, and says what is actually true.

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
