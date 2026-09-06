# AceMQ workloads studio

Design a topology, put load on it, and watch what happens.

```bash
java -jar acemq-workloads-studio.jar
# http://localhost:8480
```

One jar. No database to install, no node to run, no configuration file to write
before the first thing works.

## What it is for

`acemq-workload.jar` measures one path: one exchange, one queue, one rate. That
is the right shape for "how fast is a quorum queue" and the wrong shape for the
question people actually arrive with — **what happens to this topology, the one
we run, when Monday morning hits it**.

The studio runs a **scenario**: several exchanges, several queues each with
their own consumers, and producers aimed at the paths you choose. Every node can
be switched off without being deleted, because "what happens if this consumer
stops" is the question, and answering it by deleting the consumer loses what you
were about to put back.

Everything is counted per node. A single pair of totals cannot describe a graph:
total throughput looks healthy while one leg of a fan-out falls behind, and that
divergence is the moment worth seeing.

## What it does

**Design.** Producers on the left, exchanges in the middle, queues on the right,
which is the direction a message travels. Drag from an exchange to a queue to
bind it. The panel on the right configures whatever is selected — queue type,
consumers, prefetch, handler time, rate, message size — and the scenario is
checked as you edit, so a binding to an exchange nothing declares is a red line
here rather than a channel closure in the middle of a run.

**Presets.** Ten scenarios that answer a real question as they stand, in two
kinds.

*Measurements* isolate one variable: quorum against classic on the same traffic,
one slow consumer in a fan-out, a queue nobody is reading, prefetch high and
low, a dead-letter path under load, find the ceiling.

*Shapes* are whole topologies with the exchange types wired the way a real
system wires them, and they answer a different question — not "how fast is this
queue" but "does the routing do what I think, and what does the whole thing cost
when every path is busy at once":

| | |
|---|---|
| **Every routing rule at once** | Direct, topic and fanout side by side: 3 + 4 + 4 queues, and a producer per exchange cycling through the keys |
| **Event-driven commerce** | Orders broadcast to fulfilment and analytics, payments routed by exact type, shipping subscribed by region and by exception. Nine queues, one deliberately thin consumer |
| **A trading venue** | A market data feed fanned out to four subscribers including one too slow, instrument subscriptions by pattern, order entry by type. Small messages, 20,000/s |
| **Many writers, one queue** | Eight services into one queue. A queue is one process on one node, so this is where contention appears |

The routing shapes are worth running for the per-queue counts alone. On a
12-second run at 3,000/s over six keys, they say plainly that `orders.#` takes
the bare key `orders` as well as `orders.created.eu`, that `orders.*.eu` takes
`orders.created.eu` and **not** `orders.created.paid.eu`, and that every fanout
queue got exactly one copy of everything.

**Import.** Point it at a broker's management API and it reads the topology into
the designer, with consumers switched off. The fastest way to a useful scenario
is not drawing one — it is taking the shape that already exists and putting load
on the part in question.

**Run.** Published against consumed on one chart, because the gap between those
lines is the whole story, and the queue depths underneath, which is the same
fact as a consequence. Stop it at any time: the run reports on the window it
measured rather than throwing the work away.

**Keep.** Scenarios and every run are kept in one SQLite file under `~/.acemq`,
readings included, so a finished run can be drawn again exactly as it was
watched.

**Export.** `acemq-workload-<name>-<date>.json`, which is the file the command
line reads. A scenario designed here runs unchanged in a pipeline — that is the
reason for a designer rather than a nicer form over a YAML file. There is a YAML
export too, for pipelines that already read one.

## Running it somewhere other than your laptop

Inside a container, `localhost` is the container. Somebody types
`amqp://localhost:5672`, sees "connection refused", and is looking straight at a
broker that is running.

So the studio works out where it is. When it is containerised and the URL names
a loopback address, it tries the names that reach the machine outside —
`host.docker.internal`, `host.containers.internal`, and on Linux the default
gateway — and tells you which one answered:

> the studio is running inside a container, so localhost is not your machine.
> host.docker.internal answered instead

Nothing is rewritten silently. In Kubernetes there is no host worth reaching, so
it says to use the broker's service name instead of suggesting something that
cannot work.

**Binding and the token.** On a machine the studio binds to `127.0.0.1` and has
no password, because there is nothing to protect against on a loopback
interface. In a container that would make it unreachable, so it binds to
everything — and then it will not run without a token, which it generates and
prints at startup unless `ACEMQ_STUDIO_TOKEN` sets one. A tool that can generate
load against any broker it can reach does not get an open port for free.

```bash
# From this repository
docker build -t acemq-workloads-studio .

docker run -p 8480:8480 \
  -e ACEMQ_STUDIO_TOKEN=something-only-you-know \
  -v acemq-studio:/data \
  acemq-workloads-studio
```

Leave the token unset and the studio generates one and prints it at startup,
along with a URL that carries it. The image runs as a non-root user, writes only
`/data`, sizes its heap from the container's limit rather than the host's
memory, and its liveness endpoint is open without a token — a container that
will not answer a probe without a secret is a container that gets restarted for
ever.

**The studio and a broker together:**

```bash
docker compose up --build
# then connect to  amqp://guest:guest@broker:5672
```

Inside that network the broker is called `broker`. `localhost` is the studio's
own container, which is exactly what the first screen will tell you if you try
it.

**In Kubernetes**, the two things worth setting:

```yaml
    livenessProbe:
      httpGet: { path: /actuator/health/liveness, port: 8480 }
    readinessProbe:
      httpGet: { path: /actuator/health/readiness, port: 8480 }
    env:
      - name: ACEMQ_STUDIO_TOKEN
        valueFrom: { secretKeyRef: { name: acemq-studio, key: token } }
# and on the pod spec
terminationGracePeriodSeconds: 45
```

The grace period matters. A run holds the connection and the broker's queues,
and on SIGTERM the studio stops it and writes a report for the window it
measured — verified: a container stopped 20 seconds into a two-minute run exits
immediately and keeps the report, marked `run-was-stopped`. Killing it instead
loses the measurement and leaves the broker holding the mess.

## Configuration

| | |
|---|---|
| `SERVER_PORT` | the port. 8480 by default |
| `ACEMQ_STUDIO_ADDRESS` | what to bind to. Loopback on a machine, everything in a container |
| `ACEMQ_STUDIO_TOKEN` | the access token. Generated when the studio is exposed and nobody set one |
| `ACEMQ_STUDIO_ALLOW_REMOTE_WITHOUT_TOKEN` | run exposed with no token. There is a legitimate case for it, and it has to be set deliberately |
| `ACEMQ_STUDIO_DATABASE` | where the state lives. `~/.acemq/workloads-studio.db` by default |

## Building it

```bash
mvn package          # both modules: the library and this
java -jar studio/target/acemq-workloads-studio.jar
```

The front end is built into the jar. Maven downloads its own Node into
`target/`, so a machine with no Node builds this and a machine with the wrong
Node builds it the same way.

For working on the front end, run the studio from your IDE and then:

```bash
cd src/main/frontend
npm install
npm run dev          # http://localhost:5173, API proxied to 8480
```

## What it does not do

**It does not tell you what to change.** Every finding carries the measurement
that produced it and stops there. A tool that prints "increase prefetch to 250"
is guessing — it does not know your handler's processing time, your message
sizes, your network, or what else shares the broker — and a confident
recommendation from a tool gets followed, which makes a wrong one worse than
silence.

**It does not run two things at once.** Two load generators on one machine
measure each other, and the numbers from both are wrong in a way that is not
obvious afterwards.

**It does not offer queue types the broker will not honour.** Mirrored classic
queues were removed in RabbitMQ 4.0, and a 4.x broker accepts the policy and
ignores it. The studio asks the broker what it supports and says why an option
is not on offer rather than hiding it.
