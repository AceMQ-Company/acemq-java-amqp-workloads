#!/usr/bin/env bash
# Takes the pictures in docs/studio-guide.md, by driving the studio.
#
#   ./scripts/screenshots.sh                                    # start everything
#   ./scripts/screenshots.sh amqp://guest:guest@localhost:5781  # against a broker you have
#   SHOT_SCALE=1 ./scripts/screenshots.sh                       # smaller files
#
# The broker it starts listens on 5781 and 15781, and the URL box is legible in
# three of the pictures, so that is the port the committed images show. It is
# not 5672 because this machine usually has something there already, and a
# capture script that needs a port freed before it will run is a capture script
# nobody runs. Give it a broker of your own if you want a different URL on the
# screen.
#
# Every file this writes is a capture of the running application against a
# running broker. Nothing in here draws an interface: a screenshot in a manual
# is believed, so one that was drawn rather than taken is worse than none.
#
# It takes a few minutes, because it makes two real measured runs. The screens
# it photographs are asserted before each shot, so a capture that no longer
# matches the guide fails here rather than shipping a stale picture.
#
# Deterministic as far as it can be: the same preset, the same rates, a fixed
# viewport, a fixed device scale, UTC and en-GB. What still differs between two
# captures is the measurement -- the rates, the latencies and the wall-clock
# time a run started -- because those are photographs of a real run rather than
# drawings of one.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND="$REPO_ROOT/studio/src/main/frontend"
PORT="${SHOT_PORT:-8751}"
BROKER="${E2E_BROKER:-}"
MANAGEMENT="${E2E_MANAGEMENT:-}"
SHOT_DIR="${SHOT_DIR:-$REPO_ROOT/docs/assets}"

# A first argument that looks like a URL is the broker; anything after it goes
# to Playwright.
if [[ "${1:-}" == amqp://* || "${1:-}" == amqps://* ]]; then
  BROKER="$1"
  shift
fi

# The broker this script starts, and the only container it ever touches. One
# fixed name rather than one per process id, so a run that was interrupted
# leaves something recognisable to reuse instead of a pile of strays -- and so
# that the removal below can never reach a container somebody else started.
AMQP_PORT=5781
HTTP_PORT=15781
CONTAINER=acemq-workloads-screenshots

STUDIO_PID=""
STARTED_CONTAINER=""
WORK="$(mktemp -d)"

cleanup() {
  [ -n "$STUDIO_PID" ] && kill "$STUDIO_PID" 2>/dev/null || true
  [ -n "$STARTED_CONTAINER" ] && docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

if [ -z "$BROKER" ]; then
  if ! command -v docker > /dev/null; then
    echo "no broker given and no docker to start one:" >&2
    echo "  $0 amqp://guest:guest@localhost:$AMQP_PORT" >&2
    exit 2
  fi
  echo "==> starting $CONTAINER on $AMQP_PORT"
  # The management image rather than the plain one: the connect screen names
  # the broker's version and the queue types it honours, and that sentence is
  # the point of the first picture. Without the management API it is missing,
  # and the guide would be describing a screen the reader never sees.
  #
  # Recycled by name, and by that name alone. Every other container on the
  # machine belongs to somebody else -- a broker somebody is using, another
  # agent's fixture -- and stopping one of those to free a port is how a
  # documentation script ruins somebody's afternoon.
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  STARTED_CONTAINER=yes
  docker run -d --rm --name "$CONTAINER" \
    -p "$AMQP_PORT:5672" -p "$HTTP_PORT:15672" rabbitmq:4-management > /dev/null
  BROKER="amqp://guest:guest@localhost:$AMQP_PORT"
  MANAGEMENT="http://localhost:$HTTP_PORT"

  # A ping is not enough. The node answers one before the AMQP listener is
  # accepting, and the connect screen would then photograph "Nothing answered"
  # about a broker that came up a second later. This waits for the listener on
  # 5672 inside the container, which is what $AMQP_PORT is published from.
  echo "==> waiting for the AMQP listener"
  # Generously: a first pull, or a machine with something else building on it,
  # takes a broker well past the minute it needs when the machine is idle. But
  # a container that has died is not going to start listening however long this
  # waits, and five minutes of silence followed by a timeout is a worse message
  # than the broker's own last twenty lines.
  ready=""
  for _ in $(seq 1 300); do
    if docker exec "$CONTAINER" \
        rabbitmq-diagnostics -q check_port_listener 5672 > /dev/null 2>&1; then
      ready=yes
      break
    fi
    if ! docker ps --filter "name=^${CONTAINER}$" --format '{{.Names}}' | grep -q .; then
      echo "$CONTAINER stopped while it was starting:" >&2
      docker logs "$CONTAINER" 2>&1 | tail -20 >&2 || true
      exit 1
    fi
    sleep 1
  done
  if [ -z "$ready" ]; then
    echo "the broker never started listening on 5672 inside $CONTAINER" >&2
    docker logs "$CONTAINER" 2>&1 | tail -20 >&2 || true
    exit 1
  fi
fi
: "${MANAGEMENT:=http://localhost:15672}"
echo "==> broker: $BROKER (management $MANAGEMENT)"

JAR="$REPO_ROOT/studio/target/acemq-workloads-studio.jar"
if [ ! -f "$JAR" ] || [ -n "${SHOT_REBUILD:-}" ]; then
  echo "==> building the studio"
  (cd "$REPO_ROOT" && mvn -B -q -DskipTests -DskipITs package)
fi

# A database of its own, thrown away afterwards. The history screen is one of
# the pictures, so it has to hold this capture's two runs and nothing else.
echo "==> starting the studio on $PORT"
java -jar "$JAR" \
  --server.port="$PORT" \
  --acemq.studio.database="$WORK/studio.db" \
  > "$WORK/studio.log" 2>&1 &
STUDIO_PID=$!

for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:$PORT/" -o /dev/null 2>/dev/null; then
    break
  fi
  sleep 1
done
if ! curl -fsS "http://127.0.0.1:$PORT/" -o /dev/null 2>/dev/null; then
  echo "the studio did not start:" >&2
  tail -30 "$WORK/studio.log" >&2
  exit 1
fi

cd "$FRONTEND"
if [ ! -d node_modules ]; then
  npm ci
fi
npx playwright install --with-deps chromium > /dev/null

mkdir -p "$SHOT_DIR"
echo "==> taking the pictures into ${SHOT_DIR#"$REPO_ROOT/"}"
STUDIO_URL="http://127.0.0.1:$PORT" \
  E2E_BROKER="$BROKER" \
  E2E_MANAGEMENT="$MANAGEMENT" \
  SHOT_DIR="$SHOT_DIR" \
  npx playwright test --config playwright.screenshots.config.ts "$@"

# What landed, and what it costs the repository. These files are committed, so
# the weight is worth printing rather than discovering in a clone.
echo
cd "$SHOT_DIR"
ls -l studio-*.png | awk '{ printf "  %-28s %8.1f KB\n", $NF, $5 / 1024; total += $5 }
  END { printf "  %-28s %8.1f KB\n", "total", total / 1024 }'
