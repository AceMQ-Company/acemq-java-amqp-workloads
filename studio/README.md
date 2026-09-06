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

**Presets.** Six scenarios that answer a real question as they stand: quorum
against classic on the same traffic, one slow consumer in a fan-out, a queue
nobody is reading, prefetch high and low, a dead-letter path under load, and
find the ceiling.

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
docker run -p 8480:8480 \
  -e ACEMQ_STUDIO_TOKEN=something-only-you-know \
  -e ACEMQ_STUDIO_DATABASE=/data/studio.db \
  -v acemq-studio:/data \
  acemq-workloads-studio
```

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
