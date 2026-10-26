#!/usr/bin/env bash
# Run the 3-node cluster and the load client on one Linux box (e.g. bare metal), pinned to cores.
# For clean tail latency, boot with isolated cores (isolcpus=/nohz_full=/rcu_nocbs=) and set
# NODE_CPUS / CLIENT_CPUS to those cores. Without isolation the numbers are still useful, just noisier.
#
#   ./scripts/bench-local.sh
set -euo pipefail

# Default layout by core count: 2 cores per node plus 2 for the client on 10+ cores, 1 each on 4-9, unpinned below.
# Override with NODE_CPUS="2-3 4-5 6-7" CLIENT_CPUS=8-9 (cpu lists, space separated for the three nodes).
cores=$(nproc 2>/dev/null || echo 1)
if [ -n "${NODE_CPUS:-}" ]; then
  read -ra NODE_CPUS <<< "$NODE_CPUS"
elif [ "$cores" -ge 10 ]; then
  NODE_CPUS=(2-3 4-5 6-7); CLIENT_CPUS="${CLIENT_CPUS:-8-9}"
elif [ "$cores" -ge 4 ]; then
  NODE_CPUS=(0 1 2); CLIENT_CPUS="${CLIENT_CPUS:-3}"
else
  NODE_CPUS=(); CLIENT_CPUS=""
fi
CLIENT_CPUS="${CLIENT_CPUS:-}"
echo "cores=$cores nodes=${NODE_CPUS[*]:-unpinned} client=${CLIENT_CPUS:-unpinned}"
export CLUSTER_HOSTS="${CLUSTER_HOSTS:-localhost,localhost,localhost}"
export PORT_BASE="${PORT_BASE:-9000}"

cd "$(dirname "$0")/.."
./gradlew installDist --no-daemon -q
BIN=build/install/aeronfire/bin

# macOS has no taskset: run unpinned there (fine for a functional run, not for latency numbers).
if ! command -v taskset >/dev/null; then
  taskset() { shift 2; "$@"; }
fi
pin() { local cpus="$1"; shift; if [ -n "$cpus" ]; then taskset -c "$cpus" "$@"; else "$@"; fi; }

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT

for id in 0 1 2; do
  NODE_ID=$id pin "${NODE_CPUS[$id]:-}" "$BIN/aeronfire" > "build/node${id}.log" 2>&1 &
  pids+=($!)
done

echo "waiting for leader election..."
sleep 10

pin "$CLIENT_CPUS" "$BIN/rfq-client"
