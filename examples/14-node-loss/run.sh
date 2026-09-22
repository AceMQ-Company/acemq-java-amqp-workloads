#!/usr/bin/env bash
#
# Measure a quorum queue while it loses a node.
#
#   ./examples/14-node-loss/run.sh              # start the cluster, run, tear it down
#   CONTROL=1 ./examples/14-node-loss/run.sh    # the same run with nothing stopped
#   KEEP=1 ./examples/14-node-loss/run.sh       # leave the cluster running afterwards
#
# **Do the control run first.** One measurement of a cluster losing a node is a
# number with nothing to compare it against — p99.9 of 380ms means nothing until
# you know the same cluster idles at 40ms. Run it with CONTROL=1, keep the
# numbers, then run it again without and put the two side by side. That
# difference is the answer; either figure alone is trivia.
#
# The sequence, and why it is in this order:
#
#   1. Start three brokers and wait until all three are in one cluster. A node
#      that has started is not the same as a node that has joined, and running
#      before they agree measures a cluster forming.
#   2. Start the workload against node one, in the background.
#   3. Wait for the warm-up plus half the measured window, then ask the
#      management API which node holds the queue's leader, and stop one of the
#      other two. Stopping a follower is the repeatable experiment; stopping
#      the leader also costs an election, and the script says which node that
#      would have been so you can try it by hand.
#   4. Let the run finish and print its verdict and its exit code.
#
# The exit code is the workload's, so this script is usable as a gate.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
jar="$repo/library/target/acemq-workload.jar"
compose="$here/compose.yaml"
mgmt="http://localhost:15691"

[ -f "$jar" ] || { echo "build it first: mvn -DskipTests package" >&2; exit 3; }

cleanup() {
  if [ -z "${KEEP:-}" ]; then
    echo "--- stopping the cluster"
    docker compose -f "$compose" down -v >/dev/null 2>&1
  else
    echo "--- cluster left running; docker compose -f $compose down -v"
  fi
}
trap cleanup EXIT

echo "--- starting three brokers"
docker compose -f "$compose" up -d --wait || exit 4

echo "--- waiting for all three to be in one cluster"
for _ in $(seq 1 60); do
  running=$(docker exec node-loss-1 rabbitmqctl --formatter json cluster_status 2>/dev/null \
    | grep -o '"rabbit@node[0-9]*"' | sort -u | wc -l | tr -d ' ')
  [ "${running:-0}" -ge 3 ] && break
  sleep 2
done
[ "${running:-0}" -ge 3 ] || { echo "the cluster did not form" >&2; exit 4; }
echo "    three nodes, one cluster"

echo "--- running the workload against node one"
java -jar "$jar" -f "$here/workload.yaml" "$@" &
workload=$!

# Warm-up is 10s and the measured window is 60s, so half way through the part
# that counts is 40 seconds in. Declaring the queue and connecting takes a
# moment on top of that; a few seconds either side does not change the result.
sleep 40

leader=$(curl -fsS -u guest:guest "$mgmt/api/queues/%2F/ex.ha.orders" 2>/dev/null \
  | sed -n 's/.*"leader":"\(rabbit@node[0-9]*\)".*/\1/p')
case "$leader" in
  rabbit@node3) victim=node-loss-2; victim_node=node2 ;;
  *)            victim=node-loss-3; victim_node=node3 ;;
esac

if [ -n "${CONTROL:-}" ]; then
  echo "--- leader is ${leader:-unknown}; CONTROL run, stopping nothing"
else
  echo "--- leader is ${leader:-unknown}; stopping follower $victim_node"
  docker stop "$victim" >/dev/null
fi

wait "$workload"
code=$?
echo "--- workload exit code: $code"
exit "$code"
