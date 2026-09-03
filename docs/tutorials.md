# Tutorials

Step by step, in order, each one ending with something that runs.

The [guide](index.html) explains how a thing works and why it is that way. These
are the other shape: start with nothing, finish with a measurement you can
defend.

| | | | |
|---|---|---|---|
| 1 | [Your first measurement](tutorial-first-run.html) | Run a workload, read the report, understand the three histograms | 15 min |
| 2 | [Finding the ceiling](tutorial-finding-the-ceiling.html) | How much *can* this broker take, and how to tell the broker's limit from your own | 25 min |
| 3 | [Classic against quorum](tutorial-comparing.html) | A suite, and the cost of a guarantee as a number | 20 min |
| 4 | [Failing a build](tutorial-ci-gate.html) | Objectives, exit codes, and a gate that does not lie | 20 min |
| 5 | [When the consumer is the problem](tutorial-slow-consumer.html) | Prefetch, handler time, and telling a slow consumer from a slow broker | 25 min |

Each builds on the one before. Nothing is left as an exercise.

## Before you start

```bash
git clone https://github.com/AceMQ-Company/acemq-java-amqp-workloads
cd acemq-java-amqp-workloads
mvn -DskipTests package
```

That gives you `target/acemq-workload.jar`. Java 17 or newer.

## The broker

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:4-management
```

Management UI at <http://localhost:15672>, guest/guest. Worth having open — every
queue these tutorials create shows up there.

**One caveat that affects every number you are about to see.** The broker is
sharing a CPU with the JVM generating the load. That is fine for learning the
tool and useless as a capacity measurement. Tutorial 2 is largely about noticing
when that is what has happened.

When you are done:

```bash
docker rm -f rabbit
```
