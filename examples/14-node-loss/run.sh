#!/usr/bin/env bash
#
# What a quorum queue costs when it loses a follower. The answer is nothing you
# can measure, and that is the point of running it.
#
#   ./examples/14-node-loss/run.sh              # start the cluster, run, tear it down
#   CONTROL=1 ./examples/14-node-loss/run.sh    # the same run with nothing stopped
#   KEEP=1 ./examples/14-node-loss/run.sh       # leave the cluster running afterwards
#
# **Expect the two runs to be indistinguishable.** That is the result, not a
# failure of the experiment. A quorum queue keeps its majority when one of three
# replicas goes away: the leader never changes, no election happens, and the
# remaining two carry on committing. Six runs on one laptop gave p99 figures of
# 6ms, 8ms and 565ms with nothing stopped, and 7ms, 70ms and 794ms with a
# follower stopped — the same range, in the same order of magnitude, with the
# outliers landing on whichever leg happened to meet a busy machine.
#
# So do not read a single pair of runs as a measurement. Two runs cannot separate
# an effect this small from the noise of the host they run on, and a reader who
# compares one figure against one other figure will conclude whatever the
# scheduler decided that minute. If you want a number rather than a shape, run
# each leg several times and compare the distributions.
#
# The useful thing to take away is the shape: **a replica can go away under
# production load and the applications do not notice.** That is worth knowing
# before a rolling restart, and it is what the run demonstrates.
#
# For a cost you *can* see, stop the leader instead. That forces an election, and
# an election is a real pause rather than a statistical one. The script prints
# which node holds the leader so you can do it by hand — it does not do it for
# you, because the leader moves between runs and an experiment that targets a
# different node each time is not repeatable.
#
# The sequence, and why it is in this order:
#
#   1. Start three brokers and wait until all three are in one cluster. A node
#      that has started is not the same as a node that has joined, and running
#      before they agree measures a cluster forming.
#   2. Start the workload against node one, in the background.
#   3. Wait for the warm-up plus half the measured window, then ask the
#      management API which node holds the queue's leader, and stop one of the
#      other two.
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
