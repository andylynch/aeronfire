#!/usr/bin/env bash
# Latency report: per-hop HdrHistogram percentiles for a local 3-node cluster, once per GC setting.
#
#   ./gradlew installDist && scripts/latency-report.sh
#   GCS="G1 ZGC" COUNT=50000 LABEL=gcp-e2-standard-4 scripts/latency-report.sh
#
# Hops (all nanosecond histograms, reported in microseconds):
#   client-offer       time inside AeronCluster.offer (client side, includes back-pressure)
#   rfq-quote          client round trip: RFQ offered -> quote received
#   accept-trade       client round trip: accept offered -> trade received
#   service-rfq/accept time the clustered service spends handling the request (leader or follower)
# The round trip minus the service time is, roughly, ingress + consensus (log append, replicate to a majority,
# commit) + egress. Run it on each host you want to compare and put the reports side by side.
set -euo pipefail

BIN=${BIN:-build/install/aeronfire/bin}
COUNT=${COUNT:-20000}
WARMUP=${WARMUP:-10000}
GCS=${GCS:-"G1 ZGC"}
LABEL=${LABEL:-$(hostname)}
HEAP=${HEAP:-1g}
OUT=${OUT:-build/latency}
REPORT_S=${REPORT_S:-2}
mkdir -p "$OUT"
BIN=$(cd "$BIN" && pwd)
OUT=$(cd "$OUT" && pwd)
REPORT="$OUT/latency-$LABEL.md"

gc_flags() {
  case "$1" in
    G1) echo "-XX:+UseG1GC" ;;
    ZGC) echo "-XX:+UseZGC" ;;
    PARALLEL) echo "-XX:+UseParallelGC" ;;
    *) echo "unknown GC $1" >&2; exit 1 ;;
  esac
}

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; sleep 1; kill -9 "${pids[@]}" 2>/dev/null || true; pids=(); }
trap cleanup EXIT

{
  echo "# Latency report: $LABEL"
  echo
  echo "- date: $(date -u +%FT%TZ)"
  echo "- host: $(uname -srm), $(nproc) CPUs, $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | xargs || true)"
  echo "- java: $(java -version 2>&1 | grep -v JAVA_TOOL | head -1)"
  echo "- load: $WARMUP warm-up + $COUNT timed RFQ/accept pairs, heap $HEAP, 3 nodes and client on one host"
  echo
} > "$REPORT"

for gc in $GCS; do
  run="$OUT/run-$LABEL-$gc"
  rm -rf "$run"; mkdir -p "$run"; cd "$run"
  flags="$(gc_flags "$gc") -Xms$HEAP -Xmx$HEAP -XX:+AlwaysPreTouch"
  for i in 0 1 2; do
    NODE_ID=$i LATENCY_REPORT_S=$REPORT_S JAVA_OPTS="$flags" "$BIN/aeronfire" > "node$i.log" 2>&1 &
    pids+=($!)
    disown $!
  done
  sleep 12
  RFQ_COUNT=$COUNT WARMUP_COUNT=$WARMUP JAVA_OPTS="$flags" "$BIN/rfq-client" > client.log 2>&1 || true
  sleep $((REPORT_S + 1))
  cleanup
  cd - > /dev/null

  start=$(sed -n 's/.*timed phase start ts=//p' "$run/client.log")
  end=$(sed -n 's/.*timed phase end ts=//p' "$run/client.log")
  {
    echo "## $gc"
    echo
    echo "| hop | n | p50 us | p99 us | p99.9 us | max us |"
    echo "|---|---:|---:|---:|---:|---:|"
    grep '^hop=' "$run/client.log" | awk '{
      for (i = 1; i <= NF; i++) { split($i, kv, "="); v[kv[1]] = kv[2] }
      printf "| %s | %d | %.1f | %.1f | %.1f | %.1f |\n", v["hop"], v["n"], v["p50"]/1000, v["p99"]/1000, v["p99.9"]/1000, v["max"]/1000 }'
    # Service histograms arrive as 2-second intervals from every node; keep the timed phase, report the median
    # of the interval p50s and the worst interval for the tail (a conservative merge).
    cat "$run"/node*.log | grep '^latency ' | awk -v s="$start" -v e="$end" -v slack=$((REPORT_S * 1000)) '{
      for (i = 2; i <= NF; i++) { split($i, kv, "="); v[kv[1]] = kv[2] }
      if (v["ts"] < s || v["ts"] > e + slack) next
      h = v["hop"]; n[h] += v["n"]; c[h]++; p50[h, c[h]] = v["p50"]
      if (v["p99"] > p99[h]) p99[h] = v["p99"]
      if (v["p99.9"] > p999[h]) p999[h] = v["p99.9"]
      if (v["max"] > mx[h]) mx[h] = v["max"]
    } END {
      for (h in n) {
        m = c[h]; for (j = 1; j <= m; j++) a[j] = p50[h, j]
        for (x = 1; x <= m; x++) for (y = x + 1; y <= m; y++) if (a[y] < a[x]) { t = a[x]; a[x] = a[y]; a[y] = t }
        printf "| %s (3 nodes) | %d | %.1f | %.1f | %.1f | %.1f |\n", h, n[h], a[int((m + 1) / 2)]/1000, p99[h]/1000, p999[h]/1000, mx[h]/1000
      }
    }' | sort
    echo
  } >> "$REPORT"
done
cat "$REPORT"
