#!/usr/bin/env bash
# Runs workload-result.jq, the program action.yml uses to write
# workload-result.json, against real reports from the CLI and asserts on the
# result. Needs only jq; no broker, no build.
#
#   consumed.json           a 5s run that consumed messages
#   nothing-consumed.json   a 5s publish-only run (consumers.concurrency: 0),
#                           whose report says "endToEnd": null
#
# Usage: etc/workload-result/test.sh [--help]
set -euo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

here=$(cd "$(dirname "$0")" && pwd)
fail=0

# result REPORT [VERDICT [CODE]]: the file the action would write for REPORT.
result() {
  jq -n --arg profile p.yaml --arg mode run --arg verdict "${2:-pass}" \
        --argjson code "${3:-0}" --arg report r.json \
        --slurpfile r "$1" -f "$here/workload-result.jq"
}

# check NAME JSON JQ-EXPRESSION
check() {
  if printf '%s' "$2" | jq -e "$3" > /dev/null; then
    echo "ok   $1"
  else
    echo "FAIL $1: $3"; printf '%s\n' "$2"; fail=1
  fi
}

out=$(result "$here/consumed.json")
check "a run that consumed carries its latency and no failure" "$out" '
  .schema == "acemq.workload/v1" and .verdict == "pass" and .received > 0
  and .latency_ms.p50 > 0 and .latency_ms.p99 >= .latency_ms.p50
  and .workloads[0].latency_ms.p99 == .latency_ms.p99
  and (has("failure") | not) and (.workloads[0] | has("failure") | not)'

out=$(result "$here/nothing-consumed.json")
check "a run that consumed nothing is still written" "$out" '
  .schema == "acemq.workload/v1" and .verdict == "pass" and .exit_code == 0
  and .sent > 0 and .received == 0 and .errors == 0'
check "its latency is null, not zero" "$out" '
  .latency_ms == {p50: null, p99: null}
  and .workloads[0].latency_ms == {p50: null, p99: null}'
check "it states why" "$out" '
  .failure == "no messages were consumed, so no end-to-end latency was measured"
  and .workloads[0].failure == .failure'

out=$(result "$here/nothing-consumed.json" fail 1)
check "the verdict still follows the exit code" "$out" '.verdict == "fail" and .exit_code == 1'

# A suite of two: one consumed, one did not. No top-level latency, and the
# failure names the workload it belongs to.
suite=$(mktemp); trap 'rm -f "$suite"' EXIT
jq -s '.[0] + {workloads: (.[0].workloads + (.[1].workloads | map(.name = "idle")))}' \
  "$here/consumed.json" "$here/nothing-consumed.json" > "$suite"
out=$(result "$suite")
check "a suite names the workload that measured no latency" "$out" '
  (has("latency_ms") | not)
  and .failure == "idle: no messages were consumed, so no end-to-end latency was measured"
  and (.workloads[0] | has("failure") | not) and .workloads[1].latency_ms.p50 == null'

out=$(jq -n --arg profile p.yaml --arg mode dry-run --arg verdict pass --argjson code 0 \
        --arg report "" --slurpfile r /dev/null -f "$here/workload-result.jq")
check "no report: only what the action knows" "$out" '
  . == {schema: "acemq.workload/v1", profile: "p.yaml", mode: "dry-run", exit_code: 0, verdict: "pass"}'

exit $fail
