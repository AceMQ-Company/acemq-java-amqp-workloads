# Getting started

## Build it

> **Not published yet.** This has its own version line starting at `0.1.0` and
> has not had a release. Build it from source.

```bash
git clone https://github.com/AceMQ-Company/acemq-java-amqp-workloads
cd acemq-java-amqp-workloads
mvn -DskipTests package
```

That produces `target/acemq-workload.jar`, which is self-contained — no
classpath, no dependencies to install.

Java 17 or newer. Docker for the integration tests.

To use the DSL from your own project instead:

```xml
<repositories>
  <repository>
    <id>acemq</id>
    <url>https://acemq-company.github.io/maven/</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-java-amqp-workloads</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## A broker to point it at

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:4-management
```

**Run the generator somewhere other than the broker if you can.** A container
sharing a CPU with the JVM producing the load measures the laptop, and the report
will tell you so — but only after you have spent the run finding out.

## Your first workload

```yaml
# workload.yaml
name: first-run
broker: amqp://guest:guest@localhost:5672

topology:
  queue: hello
  routingKey: hello

publishers:
  threads: 2
  rate: 2000
  messageSize: 512

consumers:
  concurrency: 4
  prefetch: 100

warmup: 3s
runFor: 30s
```

Check it before running it:

```bash
java -jar target/acemq-workload.jar -f workload.yaml --dry-run
```

```
first-run
  broker      amqp://guest:***@localhost:5672
  TopologySpec{(default) -> hello [hello] classic}
  PublisherSpec{threads=2, rate=2000/s, Payload{512 bytes}, confirms=true}
  ConsumerSpec{concurrency=4, prefetch=100, handlerTime=PT0S}
  warmup      3s
  runFor      30s
  rules       7
```

`--dry-run` touches no broker. It resolves everything, redacts the password, and
prints what would run — which catches a typo before a two-minute wait rather
than after it.

Then run it:

```bash
java -jar target/acemq-workload.jar -f workload.yaml
```

```
running first-run against amqp://guest:***@localhost:5672 ...

  window        30s
  published     60,000  (2,000/s, offered 2,000/s)
  consumed      60,000  (2,000/s)
  queue at end  0

  end-to-end       n=60000  p50=940us  p90=1.2ms  p99=3.5ms  p99.9=11.0ms  max=14.7ms
  publish          n=60000  p50=718us  p90=955us  p99=1.7ms  p99.9=7.0ms   max=11.2ms
  send lag         n=60000  p50=201us  p90=239us  p99=941us  p99.9=7.0ms   max=12.8ms

  no findings

  result: PASSED
```

## Reading that

**`published 2,000/s, offered 2,000/s`.** The generator kept its schedule. If
these two diverge, nothing else in the report is about the broker — see
[measurement](measurement.md).

**`send lag p50=201us`.** How far behind schedule publishes went out. Small is
what you want; large invalidates the run.

**`end-to-end p50=940us`.** From when a message was *due* to when a consumer had
it. Not from when it was sent — that distinction is the whole point.

**`queue at end 0`.** The consumers kept up. A number here means the queue grew,
and the latency figures are then a function of how long you ran rather than of
the broker.

## Add an objective

```yaml
expect:
  throughputAtLeast: 1900
  p99Below: 50ms
```

Now the exit code means something:

```bash
java -jar target/acemq-workload.jar -f workload.yaml --quiet
echo $?     # 0 passed, 1 missed, 2 measured nothing
```

## Next

- [Tutorials](tutorials.md) — five, in order, each ending with something that runs
- [Command line](cli.md) — reports, formats, exit codes
- [Workload file](workload-file.md) — every setting
