# Using the studio

The walkthrough moved to [docs/studio-guide.md](../docs/studio-guide.md), which
is the same text with a picture of every screen it describes, published at
<https://acemq.org/acemq-java-amqp-workloads/studio-guide.html>.

It moved because the pictures made it worth publishing. They are captures of
the running studio, taken by [`scripts/screenshots.sh`](../scripts/screenshots.sh)
and kept in `docs/assets/` — and `docs/` is the directory the site is built
from, so a walkthrough that lives anywhere else is a walkthrough the site cannot
show. One copy, in the place that publishes it, rather than two that drift.

What this file kept is the part people opened it for:

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

- [What the studio is, and why](../docs/studio.md)
- [Using it, screen by screen](../docs/studio-guide.md)
- [The scenario file it exports](../docs/scenario-file.md)
