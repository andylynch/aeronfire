# aeronfire

A credit RFQ and pricing engine running as a replicated state machine on [Aeron Cluster](https://github.com/aeron-io/aeron).
A small, honest model of the pieces a buy-side credit trading platform needs: firm quotes, execution,
positions and spread risk, with failover that loses nothing.

Java 25 · Aeron 1.48 · SBE · Agrona · HdrHistogram · Gradle 9

## What it does

```
client ──RfqRequest──▶ ┌────────────── Aeron Cluster (3 members, Raft) ──────────────┐
       ◀────Quote───── │  sequenced log ─▶ RfqClusteredService (identical on all 3)   │
       ──QuoteAccept─▶ │     pricer · open quotes · positions · snapshot/replay       │
       ◀────Trade───── └──────────────────────────────────────────────────────────────┘
```

1. A client sends an **RFQ** for one of five illustrative private-credit loans.
2. The service prices it: base spread over a benchmark, half bid/offer, a size widening and an
   **inventory skew** that leans towards flattening risk. Price is the PV per 100 at that yield.
3. It returns a **firm quote** valid for 2s of cluster time.
4. The client **accepts**; the service checks the quote is live, belongs to that session and RFQ,
   books the **trade** and updates **positions**.
5. **Snapshots** carry counters, positions and live quotes, so a restarted member rejoins from a
   snapshot plus the log tail.

## Design decisions

| Decision | Why |
|---|---|
| Replicated state machine (Aeron Cluster) | One sequenced log gives total order, determinism and failover without a database on the hot path. Same model as the Island/INET-style sequencer designs, with Raft for leader election. |
| Determinism rules | Cluster time only, never the wall clock. Ids from counters. No hash-ordered iteration. Java floating point has been strict since 17, so the pricer gives bit-identical results on every member. |
| SBE everywhere | Fixed-layout, zero-copy codecs for ingress, egress and snapshots. Schema versioned from day one. |
| No allocation on the hot path | Preallocated codecs and buffers; quotes and positions in primitive arrays (structure of arrays), sized up front. |
| Raw enum decoding on ingress | Decoding an unknown enum value throws; an exception in the service would stall the log. Validate the raw value instead. |
| Snapshot as many small messages | Each record stays well under the snapshot publication's max message length, however many quotes are live. An end marker confirms a complete load. |
| Quote ownership check | Only the session that received a quote can accept it, and only for the same RFQ. |

## Run it

### Docker (any machine, including a Mac)

```bash
docker compose up -d --build          # 3 members
docker compose run --rm client        # RFQ round trips, prints latency percentiles
docker compose kill node0             # kill a member, run the client again: a new leader serves it
docker compose down
```

### Bare metal (latency numbers)

```bash
./scripts/bench-local.sh
```

Pins each member and the client to cores with `taskset`. For clean p99.9, boot with isolated cores
(`isolcpus`, `nohz_full`, `rcu_nocbs`) and point `NODE_CPUS` / `CLIENT_CPUS` at them. On macOS it
runs unpinned: fine for a functional check, not for publishing numbers.

### GCP (cheap, cost-controlled)

```bash
PROJECT=my-project ./deploy/gcp/up.sh                        # Spot e2-standard-4 in europe-west1
PROJECT=my-project BILLING_ACCOUNT=XXXXXX-XXXXXX-XXXXXX ./deploy/gcp/up.sh   # plus a budget alert
PROJECT=my-project ./deploy/gcp/down.sh
```

Spot pricing, a nightly stop schedule (22:00 London) and an optional budget alert. Defaults to
`europe-west1`; set `REGION=europe-west4` for the fallback.

### Requirements

JDK 25 and Gradle (via the wrapper). The build passes the flags Aeron/Agrona need on recent JDKs:
`--add-opens` for internal packages and `--sun-misc-unsafe-memory-access=allow`.

## Current-practice notes

- **`sun.misc.Unsafe` memory access is deprecated for removal** ([JDK-8331670](https://bugs.openjdk.org/browse/JDK-8331670)).
  Agrona still relies on it, hence the explicit `allow`. Watch Agrona's move towards the FFM API.
- **Generational ZGC** is the default ZGC mode on current JDKs. Compare it with tuned G1 for tail latency
  (`-XX:+UseZGC` is set by default here).
- **Virtual threads** suit I/O at the edges (gateways, admin, reporting), never the sequenced hot path.
- **Aeron** now lives under the `aeron-io` GitHub org. Dependabot groups Aeron, Agrona and SBE bumps so
  they stay in step.

## Roadmap

- [ ] Failover test: in-process 3-member cluster, kill the leader mid-flow, assert no lost or duplicated trades.
- [ ] Client reconnects and resends on leader change (idempotent RFQ ids).
- [ ] Quote expiry via cluster timers instead of check-on-accept.
- [ ] Aeron Archive replay to rebuild valuations and CS01 as of a past log position.
- [ ] Risk egress: CS01 / DV01 by issuer on every trade.
- [ ] Latency report: HdrHistogram per hop, ZGC vs G1, bare metal vs cloud.
- [ ] JMH benchmarks for the pricer and codecs.
