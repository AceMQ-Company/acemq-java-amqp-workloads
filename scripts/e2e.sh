#!/usr/bin/env bash
# Runs the studio's end-to-end tests: a real browser, the real jar, a real
# broker.
#
#   ./scripts/e2e.sh                                   # build the jar and run
#   ./scripts/e2e.sh amqp://guest:guest@localhost:5691 # against a broker you have
#
# The same script CI runs, so a failure there reproduces here with one command.
# Starting a broker is not the test runner's job, so this does it: with Docker,
# a container; without, whatever broker was passed in.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND="$REPO_ROOT/studio/src/main/frontend"
PORT="${E2E_PORT:-8749}"
BROKER="${E2E_BROKER:-}"
# A first argument that looks like a URL is the broker; anything after it is for
# Playwright, so `./scripts/e2e.sh amqp://... --grep scrolls` does what it reads
# like.
if [[ "${1:-}" == amqp://* || "${1:-}" == amqps://* ]]; then
  BROKER="$1"
  shift
fi

STUDIO_PID=""
CONTAINER=""
DATABASE="$(mktemp -d)/e2e.db"

cleanup() {
  [ -n "$STUDIO_PID" ] && kill "$STUDIO_PID" 2>/dev/null || true
  [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  rm -rf "$(dirname "$DATABASE")"
}
trap cleanup EXIT

if [ -z "$BROKER" ]; then
  if ! command -v docker > /dev/null; then
    echo "no broker given and no docker to start one:" >&2
    echo "  $0 amqp://guest:guest@localhost:5672" >&2
    exit 2
  fi
  echo "==> starting a broker"
  CONTAINER="acemq-e2e-broker-$$"
  docker run -d --rm --name "$CONTAINER" -p 5679:5672 rabbitmq:4-alpine > /dev/null
  BROKER="amqp://guest:guest@localhost:5679"

  # A broker that has not finished starting refuses the connection, and the
  # first test would report that as the studio's fault.
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" rabbitmq-diagnostics -q ping > /dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi
echo "==> broker: $BROKER"

JAR="$REPO_ROOT/studio/target/acemq-workloads-studio.jar"
if [ ! -f "$JAR" ] || [ -n "${E2E_REBUILD:-}" ]; then
  echo "==> building the studio"
  (cd "$REPO_ROOT" && mvn -B -q -DskipTests -DskipITs package)
fi

echo "==> starting the studio on $PORT"
java -jar "$JAR" \
  --server.port="$PORT" \
  --acemq.studio.database="$DATABASE" \
  > "$(dirname "$DATABASE")/studio.log" 2>&1 &
STUDIO_PID=$!

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$PORT/" -o /dev/null 2>/dev/null; then
    break
  fi
  sleep 1
done
if ! curl -fsS "http://127.0.0.1:$PORT/" -o /dev/null 2>/dev/null; then
  echo "the studio did not start:" >&2
  tail -30 "$(dirname "$DATABASE")/studio.log" >&2
  exit 1
fi

cd "$FRONTEND"
if [ ! -d node_modules ]; then
  npm ci
fi
# Only chromium. Three browsers is three downloads and three times the runtime
# for an interface whose one hard requirement is that it works in a browser at
# all.
npx playwright install --with-deps chromium > /dev/null

echo "==> running the end-to-end tests"
STUDIO_URL="http://127.0.0.1:$PORT" E2E_BROKER="$BROKER" npx playwright test "$@"
