# Command line

```bash
java -jar acemq-workload.jar -f <file> [options]
```

| Option | |
|---|---|
| `-f, --file <path>` | the workload or [scenario](scenario-file.md) file, `.yaml` or `.json`. Required |
| `--broker <url>` | run against this broker rather than the one in the file |
| `--report <dir>` | write reports into this directory |
| `--format <list>` | `html`, `md`, `json` — comma separated. Default `html,json` |
| `--dry-run` | resolve and print the configuration, run nothing |
| `--quiet` | print only the final verdict |
| `-h, --help` | usage |
| `--version` | version and exit |

## Stopping it keeps the measurements

`SIGTERM` — `docker stop`, a pod being deleted, `kill` — stops the run, waits for it
to finish measuring, and writes the report. Nothing it measured is discarded.

**The exit code is the signal's**, `143` (128 + 15), not one of the four below. A
process killed by a signal reports that signal and a shutdown hook cannot overrule
it, so the verdict lives in the report: a pipeline that stops a run deliberately
should read the report rather than the code. Measured rather than assumed — a run
stopped after twelve seconds exited `143` having written a report covering nine
measured seconds.

That matters most for a run with no fixed end (`runFor: until-stopped`), where
being stopped is the only way it ever finishes. It also means an ordinary run
interrupted halfway still produces a report of the part that happened, rather than
nothing at all.

The wait is bounded at twenty seconds, which is inside the thirty Kubernetes
allows between `SIGTERM` and `SIGKILL`. A run that cannot summarise in that time
is killed, and the report is lost — the same outcome as before, for a case that
should not arise.

## Exit codes are the interface

More important than the report format. A pipeline reads the exit code; a person
reads the report.

| | |
|---|---|
| `0` | passed |
| `1` | a **sound** run missed an objective — the broker's answer is "no" |
| `2` | a run was **invalid** — nothing was measured |
| `3` | the file is wrong, including when it is the broker that says so |
| `4` | the broker could not be reached — nobody answered |

Both kinds of file get the same codes. Which kind it is, is decided by what is in
it: a scenario names `exchanges`, `queues` and `producers`, and a workload names a
`topology`. There is no flag to remember.

These are genuinely different problems and a build that treats them alike will do
the wrong thing with each:

- **`1` vs `2`.** A missed objective is an answer. An invalid run is not — the
  generator never offered the load, and retrying it unchanged produces the same
  non-answer, forever.
- **`4` vs `1`.** A pipeline that reads "the broker refused the load" when the
  broker was never contacted sends somebody to look at broker capacity while the
  actual problem is a firewall rule.
- **`3`.** A configuration mistake never becomes a pass by retrying.

### `3` vs `4` — a refusal is not a silence

A broker that refuses something is a broker that answered. Asking it to redeclare
an exchange under a type it does not already have comes back as

```
acemq-workload: the broker refused this run: could not declare exchange orders
  the broker said: PRECONDITION_FAILED - inequivalent arg 'type' for exchange
  'orders' in vhost '/': received 'direct' but current is 'topic'
```

and that is exit `3`, not `4`. The same goes for credentials the broker would not
take, and for a queue that was supposed to already exist. Every one of them is a
disagreement between the file and the broker, which is the same kind of problem
as a misspelled setting and is fixed in the same place.

Exit `4` is reserved for the broker that never answered at all — a wrong
hostname, a closed port, a container still starting. Keeping the two apart is
the whole point of having both: `4` sends somebody to look at the network, and
sending them there over a line in their own file wastes an afternoon.

```bash
java -jar acemq-workload.jar -f workload.yaml --quiet
case $? in
  0) echo "ok" ;;
  1) echo "the broker did not meet the objective" ;;
  2) echo "the test harness could not offer the load — fix the harness" ;;
  3) echo "the workload file is wrong, or the broker refused what it asked for" ;;
  4) echo "could not reach the broker" ;;
esac
```

## Examples

### Run and print to the terminal

```bash
java -jar acemq-workload.jar -f workload.yaml
```

### Write reports

```bash
java -jar acemq-workload.jar -f workload.yaml --report reports/
```

Writes `reports/workload-20260902-195934.html` and `.json`. The timestamp means
a series of runs accumulates rather than overwriting, which is what you want when
comparing across a week of tuning.

### Just the JSON, for a pipeline

```bash
java -jar acemq-workload.jar -f workload.yaml --report out/ --format json --quiet
```

### The same file against staging and then production

```bash
java -jar acemq-workload.jar -f scenario.json --broker "amqp://guest:$PASSWORD@staging:5672"
java -jar acemq-workload.jar -f scenario.json --broker "amqp://guest:$PASSWORD@prod:5672"
```

The ordinary way this gets used, and editing the file in between is how the two
stop being the same test.

### Check a file without running it

```bash
java -jar acemq-workload.jar -f workload.yaml --dry-run
```

Resolves `${VAR}`, applies suite inheritance, validates every setting, and prints
the result with the password redacted. Touches no broker, so it is safe in a
pre-commit hook.

### Secrets from the environment

```bash
export BROKER_PASSWORD=$(vault read -field=password secret/rabbit)
java -jar acemq-workload.jar -f workload.yaml
```

with `broker: amqp://guest:${BROKER_PASSWORD}@rabbit.internal:5672` in the file.

A workload file is meant to be committed and reviewed — that is most of its
value — and a literal password is a password in your git history. An unset
variable with no default is an error rather than an empty password.

### In CI

```yaml
- name: Load test
  run: |
    java -jar acemq-workload.jar -f perf/nightly.yaml \
      --report "$RUNNER_TEMP/perf" --format html,json --quiet
  env:
    BROKER_PASSWORD: ${{ secrets.BROKER_PASSWORD }}

- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: load-test
    path: ${{ runner.temp }}/perf
```

The step fails on exit 1 or 2. Uploading the report on `always()` matters: the
run you most want to look at is the one that failed.

## Why there is no `--rate` or `--duration` flag

Everything about the workload lives in the file, deliberately.

A rate passed on the command line is a number that exists only in somebody's
shell history. The file is the reviewable artefact — it goes in a pull request,
it carries a comment explaining why the rate is what it is, and a run is
reproducible from it a year later.

Vary a setting by making a [suite](workload-file.md#a-suite) rather than by
editing a flag between runs.

## Why there is no PDF

`--format pdf` is refused, with an explanation rather than a silent failure.

Producing a PDF needs a layout engine and its fonts — a large dependency in a
tool whose entire output is a table and a list — and the result looks worse than
what a browser prints. The HTML carries `@media print` rules, so opening it and
printing to PDF gives a better document, with nothing added to the build.
