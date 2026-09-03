# Command line

```bash
java -jar acemq-workload.jar -f <file> [options]
```

| Option | |
|---|---|
| `-f, --file <path>` | the workload file, `.yaml` or `.json`. Required |
| `--report <dir>` | write reports into this directory |
| `--format <list>` | `html`, `md`, `json` — comma separated. Default `html,json` |
| `--dry-run` | resolve and print the configuration, run nothing |
| `--quiet` | print only the final verdict |
| `-h, --help` | usage |
| `--version` | version and exit |

## Exit codes are the interface

More important than the report format. A pipeline reads the exit code; a person
reads the report.

| | |
|---|---|
| `0` | passed |
| `1` | a **sound** run missed an objective — the broker's answer is "no" |
| `2` | a run was **invalid** — nothing was measured |
| `3` | the workload file is wrong |
| `4` | the broker could not be reached |

These are genuinely different problems and a build that treats them alike will do
the wrong thing with each:

- **`1` vs `2`.** A missed objective is an answer. An invalid run is not — the
  generator never offered the load, and retrying it unchanged produces the same
  non-answer, forever.
- **`4` vs `1`.** A pipeline that reads "the broker refused the load" when the
  broker was never contacted sends somebody to look at broker capacity while the
  actual problem is a firewall rule.
- **`3`.** A configuration mistake never becomes a pass by retrying.

```bash
java -jar acemq-workload.jar -f workload.yaml --quiet
case $? in
  0) echo "ok" ;;
  1) echo "the broker did not meet the objective" ;;
  2) echo "the test harness could not offer the load — fix the harness" ;;
  3) echo "the workload file is wrong" ;;
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
