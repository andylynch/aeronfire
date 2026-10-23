#!/usr/bin/env bash
# Run the 3-node cluster and the load client on one Linux box (e.g. bare metal), pinned to cores.
# For clean tail latency, boot with isolated cores (isolcpus=/nohz_full=/rcu_nocbs=) and set
# NODE_CPUS / CLIENT_CPUS to those cores. Without isolation the numbers are still useful, just noisier.
#
#   ./scripts/bench-local.sh
set -euo pipefail

NODE_CPUS=(${NODE_CPUS:-"2-3 4-5 6-7"})
CLIENT_CPUS="${CLIENT_CPUS:-8-9}"
export CLUSTER_HOSTS="${CLUSTER_HOSTS:-localhost,localhost,localhost}"
export PORT_BASE="${PORT_BASE:-9000}"

cd "$(dirname "$0")/.."
./gradlew installDist --no-daemon -q
BIN=build/install/aeronfire/bin

# macOS has no taskset: run unpinned there (fine for a functional run, not for latency numbers).
if ! command -v taskset >/dev/null; then
  taskset() { shift 2; "$@"; }
fi

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT

for id in 0 1 2; do
  NODE_ID=$id taskset -c "${NODE_CPUS[$id]}" "$BIN/aeronfire" > "build/node${id}.log" 2>&1 &
  pids+=($!)
done

echo "waiting for leader election..."
sleep 10

taskset -c "$CLIENT_CPUS" "$BIN/rfq-client"
