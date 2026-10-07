# Endurance: the soak

```bash
java -jar acemq-workload.jar endurance --workspace ~/src/acemq-amqp-libraries
```

The leak drill. Standing loads in every language, one cluster, and every client
connection torn out from under all of them once a cycle, while the run watches
what each client **process** is holding — resident memory, open descriptors,
threads — rather than what it is publishing.

## Why repetition rather than hours

What finds a leak is the number of times the leaking path runs, and in an AMQP
client that path is connection recovery: a channel not closed, a consumer tag not
forgotten, a reader thread left parked, a timer never cancelled. A day of calm
publishing runs it approximately never. The default run does it every fifteen
seconds — 240 forced recoveries per client in about an hour.

## Why from outside

None of the client libraries reports its own descriptors or threads, and teaching
each one would measure what it believes about itself rather than what the
operating system can see it holding. A pid is a pid in every language.

| OS | Memory | Descriptors | Threads |
|---|---|---|---|
| Linux | `VmRSS` in `/proc/<pid>/status` | entries in `/proc/<pid>/fd` | `Threads` in `/proc/<pid>/status` |
| macOS and other Unixes | `ps -o rss= -p <pid>` | lines of `lsof -p <pid>` | lines of `ps -M <pid>` after its header |

The macOS counts are exactly what `scripts/soak.sh` counted, so old and new
reports compare.

## The run

1. **Start or adopt the loads.** A client whose pid file
   (`.chaos/workload-<client>.pid`) names a live process is adopted and left
   running at the end; otherwise it is built if it has a build step, started, its
   pid written, and its output appended to `.chaos/workload-<client>.jsonl` — the
   same two files [`loads up`](#the-standing-loads-on-their-own-loads) leaves, so
   `loads down`, a chaos drill and the console all see it. Every load then has
   `startupSeconds` (60) to write its first sample; one that exits first, or stays
   silent past it, fails the run with its last lines of output.
2. **Warm up** (`warmupSeconds`, 120): a baseline taken during start-up measures
   start-up.
3. **Baseline**: `readings` (3) readings, `readingGapSeconds` (5) apart.
4. **Cycle** `cycles` (240) times, `cycleSeconds` (15) apart: list every connection
   through the management API and close each one, then count what is left —
   *immediately*, because clients are back within a second or two and a timed
   reading would never see the dip. Every `sampleSeconds` (30) one reading.
5. **Cool down** (`cooldownSeconds`, 60): a client mid-recovery holds two of some
   things, and that is not a leak.
6. **Final**: three more readings.
7. **Stop** the loads this run started, unless `--keep`.

## The verdict

Medians of the baseline and final readings per client, against the allowances:

| What | Allowed | Why |
|---|---|---|
| descriptors | +16 | near-flat by nature: a settled client holds a connection and a few channels however often it reconnected |
| threads | +16 | likewise |
| resident memory | 2× the baseline | noisy for reasons that are not leaks: a JVM grows towards `-Xmx`, Go holds what its GC has not returned, CPython's arenas fragment |

A client also fails if it **was not running at the end**, or if its own
`published` or `consumed` counter **did not move** between the first baseline and
the last final reading — a load that died leaks nothing, so flat numbers from it
prove nothing. And the run fails if the count right after a close was **never
below** the count while settled: then no cycle disturbed a client and nothing
above was tested.

### Known upstream

A breach a library is known to have for a reason nothing in the workspace can fix
is allowed within a ceiling measured for it, reported under `## Known upstream`,
and does not fail the run. Past the ceiling it fails like anything else. The
default list is bunny's consumer work-pool leak in the Ruby client:

```yaml
knownUpstream:
  - client: ruby
    kind: threads          # threads, memory or descriptors
    why: bunny leaks a consumer work pool on every recovery ...
    perRecovery: 1.05      # ceiling = base * factor + perRecovery * recoveries + plus
    plus: 16
  - client: ruby
    kind: memory
    why: the same pool leak retains whatever its threads were holding
    factor: 3
```

## The report

`reports/soak-<stamp>.md`, in the format `release-preflight.sh` reads: a
`**PASSED**` or `**FAILED**` line, a table of before and after per client, the
connection counts, `## What failed` with one `- ` bullet per finding, and
`## Known upstream`. The preflight blocks a library's release only on a bullet
that names its language. Beside it, `reports/soak-<stamp>.json` holds the same
facts as data with every reading, for the console's Endurance view; the raw
readings are in `.chaos/soak-<stamp>.tsv`, and the after-close counts in
`.chaos/soak-<stamp>.closes`.

Exit codes: `0` passed, `1` failed, `2` could not run.

## Options

```
  -f, --file <path>         endurance YAML; flags override it
      --workspace <dir>     the AMQP libraries workspace (default: current directory)
      --cycles <n>          forced recoveries (240)
      --cycle-seconds <n>   seconds between them (15)
      --warmup <n>          seconds before the baseline (120)
      --sample-seconds <n>  seconds between readings while cycling (30)
      --cooldown <n>        seconds after the last cycle (60)
      --clients <list>      comma separated (java,go,python,ruby,dotnet)
      --broker <url>        the AMQP URL the loads use (amqp://guest:guest@localhost:5772)
      --management <url>    the management API the fault uses (http://localhost:15772)
      --report-dir <dir>    where soak-<stamp>.md and .json go (reports)
      --keep                leave the loads this run started running
      --quiet               summary and exit code only
```

## The file

Every key is optional; an unknown key is refused rather than ignored.

```yaml
workspace: ~/src/acemq-amqp-libraries
broker: amqp://guest:guest@localhost:5772
management: http://localhost:15772     # credentials default to the broker URL's
cycles: 240
cycleSeconds: 15
warmupSeconds: 120
sampleSeconds: 30
cooldownSeconds: 60
readings: 3
readingGapSeconds: 5
startupSeconds: 60                      # for each load's first sample
allowances: { descriptors: 16, threads: 16, memoryFactor: 2 }
reportDir: reports
stateDir: .chaos
keep: false
clients: [java, go, python, ruby, dotnet]
launch:                                 # laid over the defaults field by field
  go:
    env: { GODEBUG: "gctrace=0" }
  elixir:                               # a sixth client needs only a command
    dir: ${workspace}/acemq-elixir-amqp-examples
    build: [mix, compile]
    command: [mix, run, standing_load.exs]
    env: { ACEMQ_URL: "${broker}" }
knownUpstream: []                        # replaces the default list
```

`${workspace}`, `${state}` and `${broker}` are filled in in every launch string.
The defaults start each language the way `chaos-drill.sh workload up` does, and
always run the process that publishes rather than a launcher that execs it — a pid
belonging to `go run`, `bundle exec` or `dotnet run` measures the wrong process:

| Client | Build | Command | Environment |
|---|---|---|---|
| java | — | `java -jar acemq-java-amqp-workloads/library/target/acemq-workload.jar -f config/chaos/workload/standing-load.yaml --emit-samples --quiet` | `DRILL_BROKER` |
| go | `go build -o .chaos/standing-load-go .` in `acemq-go-amqp-examples/advanced/08-…` | `.chaos/standing-load-go -broker <broker>` | |
| python | — | `acemq-python-amqp-examples/.venv/bin/python …/advanced/06-…/main.py` | `ACEMQ_URL=<broker>/` |
| ruby | — | `ruby -rbundler/setup acemq-ruby-amqp-examples/advanced/05-…/main.rb` | `BUNDLE_GEMFILE`, `ACEMQ_URL` |
| dotnet | `dotnet build advanced/05-…-csharp -o .chaos/standing-load-dotnet` | the built apphost | `ACEMQ_URL=<broker>/` |

Every one also gets `ACEMQ_EXAMPLE_SECONDS=0`: the standing loads stop after a
minute unless told otherwise.

## The standing loads on their own: `loads`

```bash
java -jar acemq-workload.jar loads up     [--workspace DIR] [--broker URL] [--clients java,go,python,dotnet,ruby] [--state DIR]
java -jar acemq-workload.jar loads status [--workspace DIR] [--state DIR] [--json]
java -jar acemq-workload.jar loads down   [--workspace DIR] [--state DIR]
```

The same loads, started and stopped without a soak around them — what a chaos
drill watches while it breaks the cluster. Same launcher as `endurance`, same
defaults and the same `launch.<client>` overrides from `-f <yaml>`, same two
files per client in `--state` (`.chaos`, relative to the workspace):
`workload-<client>.pid` and `workload-<client>.jsonl`. `scripts/chaos-drill.sh
workload up|down|status` in the workspace is a thin call to these.

- **`up`** adopts a client whose pid file names a live process, builds and starts
  the rest (`ACEMQ_EXAMPLE_SECONDS=0`, output appended to the `.jsonl`), and
  returns only once every one has written its first sample — a drill straight
  afterwards reads a timeline, not an empty file. A client that exits first, or
  writes nothing within `startupSeconds` (60), is named on stderr with its last
  lines of output, and whatever this call started is stopped again.
- **`down`** stops each client found by its pid file, whoever started it: TERM,
  ten seconds, then KILL, checked. A pid file naming a dead process is removed.
  Nothing running is still success.
- **`status`** lists each client: running or stopped, pid, how long ago its last
  sample was taken, and that sample's publish and consume rates. `--json`:

```json
{ "state": "/…/.chaos",
  "clients": [ { "client": "java", "running": true, "pid": 29674,
                 "lastSampleAgeSeconds": 1.0, "publishRate": 1999.5, "consumeRate": 2001.5,
                 "samples": "/…/.chaos/workload-java.jsonl" } ] }
```

`pid`, `lastSampleAgeSeconds` and the rates are `null` when there is nothing to
report. `--clients` (and `-f`) work with all three, so `down --clients go` stops one;
`--broker` is accepted by `down` and `status` too, and ignored there.

Exit codes: `0` done, `2` could not — a client that would not start or would not
stop, or a bad argument. `status` exits `0` whatever it finds.

## From the console

The studio's Endurance view shows the newest report (the JSON when there is one,
the markdown of an older script-written report otherwise) and has **Start a
soak**: cycles, seconds between closes, warm-up and clients. It refuses while a
load or a scenario run is going (and they refuse while a soak is), checks that the
broker and its management API answer before it starts anything, and shows the
phase, `cycle n/N` and every client's readings as they are taken. **Stop the
soak** stops the loads it started; no report is written for a soak stopped early.
The studio has to be pointed at the workspace (`ACEMQ_STUDIO_DRILL_WORKSPACE`).
