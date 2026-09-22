# Using the studio

Everything the studio does, in the order you meet it. [The studio](studio.md)
says what it is and why; this says how to work it.

The pictures are captures of the running application, taken by
[`scripts/screenshots.sh`](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/blob/main/scripts/screenshots.sh),
which drives the studio through this walkthrough against a real broker. If a
screen here looks different from the one in front of you, the one in front of
you is right and this page is behind.

- [Starting it](#starting-it)
- [1. Connect to a broker](#1-connect-to-a-broker)
- [2. Design a scenario](#2-design-a-scenario)
- [3. Say what it must prove](#3-say-what-it-must-prove)
- [4. Run it](#4-run-it)
- [5. Keep the result](#5-keep-the-result)
- [6. Compare two runs](#6-compare-two-runs)
- [Presets](#presets)
- [Files, in and out](#files-in-and-out)
- [Settings](#settings)
- [When something goes wrong](#when-something-goes-wrong)

## Starting it

```bash
java -jar acemq-workloads-studio.jar
```

Then open <http://localhost:8480>. Java 17 or newer; no installation, no
database to set up, no configuration file.

In a container:

```bash
docker run --rm -p 8480:8480 -v acemq-studio:/data \
  ghcr.io/acemq-company/acemq-workloads-studio:latest
```

The volume is worth it: without it the run history lives in the container's
writable layer and disappears with it.

A different port, or somewhere else to keep the database:

```bash
java -jar acemq-workloads-studio.jar --server.port=9000 \
  --acemq.studio.database=/tmp/scratch.db
```

## 1. Connect to a broker

The first screen, and for a moment the only one. Nothing else works without a
broker — the designer asks it which queue types it honours, and a scenario is
only worth drawing if it can be run.

| Field | |
|---|---|
| **Broker (AMQP)** | `amqp://guest:guest@localhost:5672`, or `amqps://…` for TLS |
| **Management API** | `http://localhost:15672`. Optional, and worth having |

![The connect screen. A heading, Connect to a broker, then two boxes: Broker
(AMQP) holding amqp://guest:guest@localhost:5781, and Management API holding
http://localhost:15781. Below them a green panel reading "Found a broker at
localhost:5781", with "RabbitMQ 4.3.6 — Classic, Quorum, Stream" underneath it.
Two buttons at the foot: Continue, and Check
again.](assets/studio-connect.png)

It checks as soon as the screen opens, and again whenever you press **Try
again** or **Check again**. Three things can come back:

- **Found a broker.** Press **Continue**. The line under it is the broker's own
  version and the queue types it honours, which is what the management API was
  asked for.
- **Found it, but the management API did not answer.** You can continue. Without
  it the studio cannot tell which queue types this broker honours, so it offers
  only classic and quorum, and **Import from broker** is unavailable.
- **Nothing answered.** It shows every URL it tried and what each said, which is
  usually enough: a refused connection, a wrong password and a closed port read
  differently.

The management API is HTTP and wants a user of its own. The studio sends the one
written into the AMQP URL, which on every broker anybody points this at is the
same user — so filling in the management box is usually all there is to it.

**Inside a container**, `localhost` means the container. The studio knows, and
tries `host.docker.internal`, `host.containers.internal` and the default gateway
before giving up — then tells you which one answered and uses that for the run.

### TLS and mutual TLS

An `amqps://` URL opens the TLS section. The studio completes a **handshake**,
not a TCP connect, and reports the protocol, whether the chain verified, what
the broker presented, when it expires, and whether it asked for a client
certificate in return.

| | |
|---|---|
| **Certificate authority** | The authority that signed the broker's certificate. Paste the PEM or give a path |
| **Client certificate and key** | For mutual TLS, when the broker asks who you are. PEM as it comes; the studio builds the keystores itself |
| **Accept development certificates** | The AceMQ generator stamps its certificates development-only and the library refuses them unless this is on |
| **Trust any certificate** | Encrypts and proves nothing. For a first run against a broker whose certificate nobody can find |

An encrypted private key is refused, with the `openssl` command that decrypts
one. Holding your passphrase would make the studio the thing that leaked it.

## 2. Design a scenario

The **design** tab. The canvas on the left, the inspector on the right, and what
you have selected decides what the inspector shows.

![The design tab. Across the top: tabs for design, run, presets and history, the
broker and management URLs, and a green Run button. Under that a toolbar reading
slow-consumer, with + exchange, + queue, + producer, Save, Open file, Export
JSON, YAML and Import from broker. The canvas below holds four nodes wired left
to right — a producer "orders" publishing 1,500 a second at 512 bytes, a fanout
exchange "orders", and two classic queues bound to it: "orders.fast" with four
consumers and "orders.slow" with one. On the right the inspector shows the
scenario itself: its name, what it is for, a warm-up of 3s, a measured window of
25s, and a switch to declare the topology before
running.](assets/studio-canvas.png)

### The canvas

| To | Do |
|---|---|
| Add a node | **+ exchange**, **+ queue** or **+ producer** in the toolbar |
| Bind a queue to an exchange | Drag from the dot on the exchange's right edge to the dot on the queue's left |
| Select something | Click it |
| Move things | Drag them; the layout is yours |
| Zoom or fit | The controls at the bottom left |

Bindings are the edges, with the routing key on them. While a run is going, the
edges carrying traffic animate and each node shows its own numbers.

A scenario that arrives rather than being drawn — a preset, an opened file, an
imported topology — lands on whatever view the canvas already had, so a large
one can arrive partly off-screen. **Fit**, the lower of the controls at the
bottom left, puts all of it back in frame.

### The inspector

**Nothing selected** — the scenario itself: its name, what it is for, the warm-up
and the measured window, and whether to declare the topology before running.

> Turn **declare** off against a real environment. Declaring there is either
> refused for mismatched arguments or, worse, quietly creates something subtly
> different from what production runs — and then measures that instead.

**An exchange** — its name and type (topic, fanout, direct, headers), and whether
it takes part.

**A queue** — its name; its type as radio cards, each saying what it costs, with
anything this broker will not honour disabled and saying why; a dead-letter
exchange; its bindings; its arguments; its consumers.

![The inspector with a queue selected. The name orders.slow at the top, then
Type as four radio cards: Classic, chosen and outlined in green; Classic,
mirrored, greyed out and saying mirrored classic queues were removed in RabbitMQ
4.0 and this broker is 4.3.6; Quorum; and Stream. Below them a dead-letter
exchange set to "none: rejected messages are dropped", and then Bound to, with
one binding — the orders exchange, an empty routing key, and an Unbind button
beside it — and a + binding button underneath.](assets/studio-inspector.png)

- **Bound to** — every binding, with the exchange and the routing key both
  editable, an **Unbind** button, and **+ binding**. A binding left pointing at
  an exchange that no longer exists stays visible and says so rather than
  disappearing.
- **Arguments** — what the broker is told at declaration beyond the type:
  `x-max-length`, `x-message-ttl`, `x-max-age` on a stream. Anything that reads
  as a number is sent as one, because `x-max-length: "1000"` is refused where
  `1000` is accepted.
- **Consumers** — how many, prefetch, how long the handler takes, and how often
  it fails. **Consumers running** switches them off without removing them: the
  queue keeps filling and the run measures what the backlog costs, which is
  usually the question.

**A producer** — where it publishes, its routing keys (comma separated, used in
turn), its rate and message size, publisher confirms, and whether it takes part.

> Say the rate and let the studio work out the threads. A rate of **0** is
> unthrottled: it finds the ceiling, and makes the latency meaningless while
> doing it — the report will say so.

### Checking as you go

Every edit is checked against what a broker would accept. Problems appear in a
red banner and block the Run button; warnings appear in an amber one and do not.
A binding to an exchange nothing declares is a problem. A queue nobody consumes
is a warning, because it is a legitimate thing to measure.

### Starting from something that exists

- **Import from broker** reads the topology off the management API, with
  consumers switched off. The fastest way to a useful scenario is not drawing one
  — it is taking the shape that already exists and putting load on the part in
  question.
- **Open file** opens a scenario file, JSON or YAML: the one a pipeline runs, or
  one exported from here a month ago.
- **presets** gives you ten that answer a real question as they stand.

## 3. Say what it must prove

Every queue and every producer has a **What it must prove** panel. What is left
blank is not checked; anything set decides the exit code when the exported file
runs from a pipeline.

On a queue:

| | |
|---|---|
| **p99 under**, **p99.9 under** | End-to-end latency, from when a message was *due* |
| **Handles at least, a second** | Messages a second its consumers must manage |
| **Must not be deeper at the end than at the start** | No growing backlog. Not checked for a stream, which retains what it has served |

![The What it must prove panel at the foot of a queue's inspector. p99 under
holds 150ms and p99.9 under holds 500ms, side by side; Handles at least, a
second holds 1400; a switch below them, "Must not be deeper at the end than at
the start", is on. Under the lot, a line saying what is left blank is not
checked.](assets/studio-objectives.png)

On a producer:

| | |
|---|---|
| **At least, a second** | What it must actually offer |
| **Within % of the rate** | How far the achieved rate may fall short of the configured one |
| **Every publish must succeed** | No failed publishes |

Set these **per node**, which is the point: the audit leg may lag as much as it
likes while the fulfilment leg must not, and one overall p99 would average away
exactly that distinction.

A missed objective is a `FAILED` finding naming the node and both numbers, and
`java -jar acemq-workload.jar -f scenario.json` exits `1`.

## 4. Run it

Press **Run**. The studio resolves the broker URL first — inside a container the
one you typed may name the container rather than your machine — and then starts.

The **run** tab shows:

- **Published against consumed**, on one chart. While the two lines sit together
  the system is keeping up; the gap between them is the backlog forming.
- **What is waiting, queue by queue**, underneath. The same fact as a
  consequence.
- **A card per producer and per queue**, with its own rate and totals.

![A run in progress. A green chip reads "measuring", beside it "14s elapsed",
and at the right a red "Stop, and report on what it measured" button. Below,
the chart "Published against consumed": both lines climb to about 1,500 a
second in the first three seconds and then run flat and together. Under it,
"What is waiting, queue by queue": orders.fast sits on zero for the whole run
while orders.slow climbs in a straight line past twenty thousand
messages.](assets/studio-run.png)

Rates are per interval rather than averages since the start: an average cannot
show a stall, it dips a little and recovers.

The phase chip says what is happening. **Warming up** means the numbers are being
thrown away — class loading, JIT and the first collection land there rather than
in your p99.

**Stop, and report on what it measured** ends the measured window early and still
produces a report. It is not an abort: publishers stop, consumers get their usual
moment to finish what is in flight, and the report covers however long the run
actually lasted. A run stopped after twenty seconds trips `run-was-long-enough`,
which is exactly what should happen.

One run at a time. Two load generators on one machine measure each other.

### Reading the verdict

| | |
|---|---|
| **Passed** | Sound run, everything it was asked for held |
| **Failed** | Sound run, something it was asked for did not hold — the broker's answer is "no" |
| **Invalid** | The generator never offered the load. This says nothing about the broker; fix the harness |

![The verdict of a finished run, in a red-tinged panel: "Failed", and under it
"25s measured · 37,502 published · 39,832 consumed". Then three findings, each
a rule name with its severity in front of it, the measurement that produced it,
and a sentence saying what that measurement means. WARNING
consumers-kept-up:orders.slow, "orders.slow grew from 4133 to 39307 messages
over the run". FAILED expected-p99:orders.slow, "orders.slow p99 was 26038.2ms,
and was asked for under 150ms". FAILED expected-p99.9:orders.slow, the same
story at p99.9.](assets/studio-verdict.png)

Every finding carries the measurement that produced it, and none of them tell you
what to change. A tool that prints "increase prefetch to 250" is guessing, and a
confident wrong recommendation is worse than silence.

## 5. Keep the result

Scenarios, runs and every reading go into one SQLite file, `~/.acemq/workloads-studio.db`
by default.

- **Save** keeps the scenario. Saved scenarios are listed in **history**, where
  **Open** puts one back on the canvas.
- **Export JSON** / **YAML** downloads `acemq-workload-<name>-<date>.json`, the
  file the command line reads.
- On a finished run, **HTML**, **Markdown** and **JSON** in the run toolbar save
  the report — the same document the command line writes, so what goes into a
  ticket is the report rather than somebody's memory of it.
- **history** lists every run: the scenario, the broker with its password
  redacted, when it started and how it ended. **Open** draws a finished run again
  exactly as it was watched. **Delete** forgets it and its readings.

![The history tab. A Saved scenarios panel at the top with one row —
slow-consumer, its description, and Open and Delete buttons. Under it a Runs
panel: a line saying to tick two runs to compare them, then a table with a tick
box per row and columns for the scenario, the broker, when the run started and
the result. Two runs of slow-consumer are listed, both against
amqp://guest:***@localhost:5781 with the password replaced by asterisks, both
finished, both reading failed in red.](assets/studio-history.png)

The 200 most recent runs are kept and older ones are dropped as new ones finish.
`--acemq.studio.keep-runs=1000` if you want more.

## 6. Compare two runs

In **history**, tick two finished runs and press **Compare**.

Tick the older one first. The one ticked first is the **before** column, and the
table lists the newest run at the top — so ticking straight down the list puts
the new run in **before** and reports every improvement as a regression.

> Nothing empties a queue between runs. A run that ended with a backlog leaves
> it there, and the next run against the same broker starts behind by however
> much — which shows up as a latency ten times anything the first run saw, and
> is the harness rather than the change being measured. Delete the queues, or
> use a fresh broker, before comparing two runs that both build a backlog.

![The comparison table, headed "slow-consumer → slow-consumer" and "10 of 12
measurements moved by more than 5%". A row per measurement the two runs have in
common, with columns for the node, the measurement, before, after and the
change. Doubling the producer's rate doubled what came out — published 37,502 to
75,002, +100.0% better — and cost the fast leg its latency: orders.fast p99 went
from 2.8ms to 88.1ms, shown as ×31.7 worse. orders.slow consumed 93 a second in
both runs, reported as same.](assets/studio-comparison.png)

Every measurement the two have in common, with the direction made explicit: a
latency that went up is **worse**, a rate that went up is **better**, and the
table says which rather than leaving you to work it out from the sign.

Anything inside ±5% is reported as **same**. Two runs of one configuration differ
by a few percent on ordinary hardware, and a tool that called that a regression
would be switched off within a week. A node only one run had is shown as **only
before** or **only after** rather than dropped — comparing a scenario with a
queue against the same scenario without it is a comparison, and hiding the queue
would hide the only thing that changed.

## Presets

The **presets** tab. Click one and it lands on the canvas, yours to edit.

*Measurements* isolate one variable:

| | |
|---|---|
| **Quorum against classic** | What does the replication actually cost at my rate? |
| **One slow consumer in a fan-out** | One leg is slower than the rest. What happens to the others? |
| **A queue nobody is reading** | How fast does a backlog build, and what does the broker do about it? |
| **Prefetch, high and low** | Is prefetch the thing limiting this, or is it the handler? |
| **A dead-letter path under load** | Handlers are rejecting traffic. Where does it go, and how fast? |
| **Find the ceiling** | How much can this broker take before it stops keeping up? |

*Shapes* are whole topologies:

| | |
|---|---|
| **Every routing rule at once** | Direct, topic and fanout together: does each deliver what it should? |
| **Event-driven commerce** | Orders broadcast, payments by exact type, shipping by pattern |
| **A trading venue** | Market data fanned out to four desks, one of them slow; orders by instrument |
| **Many writers, one queue** | Eight services into one queue. Where does the contention show up? |

Most carry their objectives already. `find-the-ceiling` deliberately carries
none: it is unthrottled, and its latency means nothing.

## Files, in and out

The studio and the command line read and write the same file, which is the whole
point of the designer:

```bash
# designed here, exported, and run in a pipeline
java -jar acemq-workload.jar -f acemq-workload-orders-2026-09-07.json \
  --broker "amqp://guest:$PASSWORD@staging:5672" \
  --report reports/ --format html,json --quiet
```

`${VAR}` in a file is resolved from the environment by the command line, so a
password never has to live in a file that gets committed. The studio does **not**
resolve it when opening a file — doing so would read the studio's own environment
on behalf of whoever opened the file and hand the value back.

[Every field in the format](scenario-file.md).

## Settings

Command-line flags, or the environment variable beside them.

| | |
|---|---|
| `--server.port` / `SERVER_PORT` | The port. 8480 |
| `--acemq.studio.database` / `ACEMQ_STUDIO_DATABASE` | Where state lives. `~/.acemq/workloads-studio.db` |
| `--acemq.studio.address` / `ACEMQ_STUDIO_ADDRESS` | What to bind to. Loopback on a machine, everything in a container |
| `--acemq.studio.token` / `ACEMQ_STUDIO_TOKEN` | The access token. Generated and printed when the studio is exposed and nobody set one |
| `--acemq.studio.keep-runs` | How many finished runs to keep. 200 |
| `ACEMQ_STUDIO_ALLOW_REMOTE_WITHOUT_TOKEN` | Run exposed with no token. There is a legitimate case for it, and it has to be set deliberately |

On a machine the studio binds to loopback and needs no token. Exposed — which is
what it does in a container, because loopback there would be unreachable — it
requires one: a load generator open on a network is a way to point traffic at
somebody else's broker.

## When something goes wrong

**"Nothing answered."** Everything tried is listed with what each said. From a
container, check whether the broker is on the host rather than in the network.

**The queue type I want is disabled.** The broker was not asked. Give the studio
a management URL and press Check again. Mirrored classic queues are disabled
against RabbitMQ 4.0 and later because they were removed there.

**The run says INVALID.** The generator did not offer the load, so the run
measured the generator. Lower the rate, or give the producer more threads, and
run it again. Reporting this as a failure would blame the broker for the client's
limit.

**The p99 looks impossible on an unthrottled run.** It is. An unthrottled
producer stalls when the broker stalls, so the latency it records is the
broker's service time rather than the wait. Find the ceiling first, then measure
latency below it.

**A run will not start: "a run is already going".** One at a time, deliberately.
Stop the other one, or wait for it.

**The studio will not start: the port is taken.** `--server.port=9000`.
