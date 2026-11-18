# Turns the tool's JSON report into workload-result.json (schema
# acemq.workload/v1). action.yml runs it; etc/workload-result/test.sh runs it
# against real reports kept next to it.
#
# Only what the report states. A workload report carries one entry per
# workload in the file; a scenario report carries totals and per-queue
# latency, so it has no single rate or latency to give.
#
# The report writes "endToEnd": null when there were no latency samples, as in
# a publish-only run. Latency is then null, never 0: zero would read as a
# measurement. `failure` says why it is missing, and is absent otherwise.

def latency:
  if .endToEnd == null then {p50: null, p99: null}
  else {p50: (.endToEnd.p50Micros / 1000), p99: (.endToEnd.p99Micros / 1000)} end;

def failure:
  if .endToEnd != null then null
  elif (.consumedTotal // 0) == 0 then "no messages were consumed, so no end-to-end latency was measured"
  else "no end-to-end latency samples were recorded" end;

def stated($f): if $f == null then {} else {failure: $f} end;

($r[0] // null) as $doc
| {schema: "acemq.workload/v1", profile: $profile, mode: $mode, exit_code: $code}
+ if $doc == null then {}
  elif ($doc.workloads | type) == "array" then
    ($doc.workloads) as $w
    | {sent: ($w | map(.publishedTotal) | add),
       received: ($w | map(.consumedTotal) | add),
       errors: ($w | map(.failedTotal) | add),
       workloads: ($w | map({name, duration_s: .durationSeconds, sent: .publishedTotal,
         received: .consumedTotal, errors: .failedTotal,
         rate_msgs_per_s: .consumeRatePerSecond, valid, passed,
         latency_ms: latency} + stated(failure)))}
    + if ($w | length) == 1 then
        {duration_s: $w[0].durationSeconds, rate_msgs_per_s: $w[0].consumeRatePerSecond,
         latency_ms: ($w[0] | latency)} + stated($w[0] | failure)
      else
        # Several workloads have no single latency; name each that is missing one.
        [$w[] | select(failure != null) | "\(.name): \(failure)"] as $f
        | stated(if $f == [] then null else $f | join("; ") end)
      end
  elif $doc.scenario != null then
    {scenario: $doc.scenario, duration_s: ($doc.durationMs / 1000),
     sent: $doc.totalPublished, received: $doc.totalConsumed,
     errors: (($doc.producers // []) | map(.failed // 0) | add)}
  else {} end
+ (if $doc == null then {} else {report_path: $report} end)
+ {verdict: $verdict}
