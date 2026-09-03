# Licence and warranty

AceMQ AMQP workloads is
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You may use it
in production, commercially, without asking and without paying.

## No warranty

The libraries are provided **"as is", without warranties or conditions of any
kind**, and the authors and contributors accept no liability for damages arising
from their use.

That is not a notice added here for comfort — it is
[section 7](https://www.apache.org/licenses/LICENSE-2.0#no-warranty) and
[section 8](https://www.apache.org/licenses/LICENSE-2.0#no-liability) of the
licence, and it is the same footing as every other Apache-licensed dependency
already running in your systems.

Practically, it means the same thing it means for any open-source library you
depend on: **test it against your workload before you rely on it.** The
integration suite runs against a real RabbitMQ 4.x broker in a container, and
what it covers is stated plainly in the documentation — including the parts of
the management API that are not wrapped yet.

This tool generates load. Pointed at a production broker it will publish as many
messages as you configured, declare the topology it was given, and compete for
that broker's capacity with everything else using it. A workload file that was
right for a staging environment is not automatically safe against production, and
the tool has no way to know which one it is talking to.

It also reports numbers that people make decisions with. Read
[measurement](measurement.md) before quoting any of them: a run whose generator
could not offer its load is marked INVALID for a reason, and the figures in it
describe the client rather than the broker.

## If you need more than a licence gives you

Warranties, indemnity, response times and someone accountable come from a
contract, not from a licence. That is what
[AceMQ Enterprise support](https://acemq.com) is for: architecture review,
production readiness, TLS and permission design, and incident response.

The libraries are complete and free to use without it, and are not crippled to
sell it.

## Trademarks

The licence grants no trademark rights
([section 6](https://www.apache.org/licenses/LICENSE-2.0#trademarks)).

**RabbitMQ is a trademark of Broadcom Inc. and/or its subsidiaries.** AceMQ is an
independent project, is not affiliated with, endorsed by or sponsored by
Broadcom, and references to RabbitMQ describe compatibility only.

AMQP is an open standard maintained by OASIS. This tool generates AMQP 0-9-1
load through AceMQ, and uses RabbitMQ's management API for topology discovery and
queue depth.

Comparisons in this documentation between queue types, or between this tool and
`rabbitmq-perf-test`, describe measured behaviour and are not claims about the
products' merits.

## Contributions

Contributions are accepted under the same licence, per
[section 5](https://www.apache.org/licenses/LICENSE-2.0#contributions): anything
you deliberately submit for inclusion is licensed to the project under Apache-2.0
unless you state otherwise.
