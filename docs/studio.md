# The studio

```bash
java -jar acemq-workloads-studio.jar
```

Then open <http://localhost:8480>.

A browser interface for the same engine the command line runs: design a
topology, choose what runs against it, watch it happen, and keep the result. What
it exports is a [scenario file](scenario-file.md), which is what a pipeline
reads — so a scenario drawn on a screen runs unchanged in CI rather than being
described to somebody who then writes YAML by hand.

The full walkthrough, screen by screen, is in
[studio/USAGE.md](https://github.com/AceMQ-Company/acemq-java-amqp-workloads/blob/main/studio/USAGE.md).
This page is what it is and why.

## A broker first

Nothing in the studio works without one, so the first screen is a connection and
the rest is not reachable until it answers. That is not ceremony: the designer
asks the broker which queue types it honours, and drawing a scenario against
assumptions is how you find out at the end of a run that mirrored classic queues
were removed in RabbitMQ 4.0.

The check completes a **handshake**, not a TCP connect. Port 5671 answers a
socket whether or not the certificate on it is one this client will accept, so a
tool reporting "reachable" on a connect sends you to press Run and meet the real
failure a minute later as a stack trace. `amqps://` turns on a TLS section: a
certificate authority, a client certificate and key for mutual TLS, and two
switches named for what they do.

## What you can draw

Exchanges, queues with their own bindings and consumer counts, and producers
aimed at chosen keys. Bindings are the edges: drag from an exchange to a queue to
make one, click it to change its key or remove it.

Everything can be switched **off** rather than deleted, and that matters more
than it sounds: "what happens if this consumer stops" is the question people run
these things to answer, and the settings you were about to put back are still
there.

Every queue and producer has a *What it must prove* panel — a p99 ceiling, a rate
floor, "must not grow", "no failed publishes". Those travel in the exported file
and decide the command line's exit code, so a scenario designed here can fail a
build on a number. [The fields](scenario-file.md#objectives).

## Presets

Ten scenarios that answer a real question as they stand, in two kinds.
*Measurements* isolate one variable: quorum against classic on the same traffic,
one slow consumer in a fan-out, a queue nobody reads, prefetch high against low,
a dead-letter path under load, find the ceiling. *Shapes* are whole topologies —
all three exchange types wired the way each is meant to be used, an e-commerce
event flow, a trading venue, eight writers into one queue.

Most carry their objectives, and the choices are deliberate: the comparison
presets assert that both legs kept up and that the generator offered its load,
because a comparison where the load was never applied describes the generator.
`find-the-ceiling` asserts nothing at all — it is unthrottled, and its latency
means nothing.

## Watching a run

Published against consumed on one chart, because the gap between those two lines
is the entire story, and the queue depths underneath, since a growing depth is
the same fact expressed as a consequence. Rates are per interval rather than
averages: an average cannot show a stall, it dips a little and recovers.

Stop it whenever you like. The run reports on the window it measured rather than
throwing the work away — and a run stopped after twenty seconds trips
`run-was-long-enough`, which is exactly what should happen.

## Keeping it

Scenarios, runs and every reading go into one SQLite file under `~/.acemq`, so a
finished run can be drawn again exactly as it was watched, and two runs can be
compared afterwards without re-running either. A finished run hands over its
report as HTML, Markdown or JSON, written by the same code the command line uses.

## In a container

The studio detects that it is in one, and says so. `localhost` inside a container
is the container, and a broker on the machine outside is reachable at
`host.docker.internal`, `host.containers.internal` or the default gateway
depending on where it is running — so the studio tries them, tells you which one
answered, and runs against that rather than failing with a connection refused
that looks like the broker's fault.

Where it binds follows from the same fact: loopback on a machine, everything in a
container — and there it requires a token, printed once at startup, because a
load generator reachable on a network is a way to point traffic at somebody
else's broker.

## Why it exists

`rabbitmq-perf-test` is maintained by the RabbitMQ team and battle-tested, and
for one queue and one publisher it is the right tool. What it cannot do is put a
**topology** under load: several queues with different types and different
consumer counts, fed by producers on different keys, measured per node so one leg
falling behind is visible rather than averaged away.

That is the gap the studio is for, and the reason it exports a file rather than
keeping the design to itself.
