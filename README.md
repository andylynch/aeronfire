# aeronfire

A credit RFQ and pricing engine running as a replicated state machine on [Aeron Cluster](https://github.com/aeron-io/aeron).
A small, honest model of the pieces a dealer needs on a credit RFQ venue: a FIX gateway, firm quotes,
pre-trade credit and client tiering, execution, positions and spread risk, with failover that loses nothing.

Java 25 · Aeron 1.53 · Artio (FIX) · SBE · Agrona · HdrHistogram · Gradle 9

## What it does

```
 venue sim ──FIX R──▶ ┌──────────┐ ─RfqRequest─▶ ┌──────── Aeron Cluster (3 members, Raft) ────────┐
 (Tradeweb- ◀─FIX S── │   FIX    │ ◀───Quote──── │ sequenced log ─▶ RfqClusteredService (×3)        │
  style)    ─FIX AJ─▶ │ gateway  │ ─QuoteAccept─▶ │  credit · tiers · pricer · quotes · positions    │
            ◀─FIX 8── │ (Artio)  │ ◀─Trade/Reject │  snapshot/replay                                 │
            ◀─FIX AG─ └──────────┘                └──────────────────────────────────────────────────┘
```

1. The **venue simulator** plays a Tradeweb-style RFQ venue over FIX 4.4: clients (accounts 0–9, mixed
   tiers) ask for prices on five illustrative private-credit loans, and several dealers compete.
2. The **FIX gateway** (Artio acceptor) turns each QuoteRequest into a cluster RFQ.
3. The **clustered service** checks **credit and tier**, then prices: base spread over a benchmark,
   half bid/offer, size widening, **inventory skew** and a **tier adjustment**. Price is PV per 100.
4. It returns a **firm quote** whose time-to-live depends on the client's tier.
5. The venue invents competing dealers' prices and lifts or hits us only if we're best. On accept the
   service re-checks the quote and credit, books the **trade**, updates **positions** and **utilisation**.
6. Rejects go back as FIX QuoteRequestReject (tier, credit, instrument) or a rejected ExecutionReport.
7. **Snapshots** carry counters, positions, live quotes and credit lines, so a restarted member
   rejoins from a snapshot plus the log tail.

### Credit and tiering

| Tier | Spread adj. | Max ticket | Quote TTL | Demo clients | Demo limit |
|---|---|---|---|---|---|
| 1 | −5bp | 50mm | 3s | 0–1 | 500mm |
| 2 | 0bp | 25mm | 2s | 2–5 | 200mm |
| 3 | +10bp | 10mm | 1s | 6–9 | 50mm |

Utilisation is gross traded notional. It's checked on the RFQ (don't quote what the client can't trade)
and again on accept (other trades may have used the headroom since). `CreditUpdate` sets a line at
runtime; `CreditReset` is the start-of-day reset. Credit lives in the cluster, so two gateways can never
spend the same headroom.

### Verified

Run locally (3 members, gateway and venue on one 2-core box):

- 1,000 FIX RFQs → 837 quotes, 163 rejects (124 above tier max, 39 credit), 120 trades, nothing lost.
- 7,000 RFQ/trade round trips on the raw cluster client with no rejects (credit reset between phases).
- Leader killed between runs: a new leader is elected and trading continues.

Latency numbers from that box aren't meaningful (six JVMs sharing two cores); use
`scripts/bench-local.sh` on isolated cores for those.

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
| Rejects name what they reject | Each reject carries the rejected template id, so the gateway knows whether an id is an RFQ or a quote. |
| Artio for FIX | Aeron-native FIX engine: the gateway's FIX journal and the cluster share one transport and tooling. |
| Gateway owns id mapping | One cluster session carries every venue's flow; the gateway maps FIX QuoteReqID/QuoteID to cluster ids. |

## Run it

### Docker (any machine, including a Mac)

```bash
docker compose up -d --build          # 3 members + FIX gateway (FIX on localhost:9880)
docker compose run --rm venue         # Tradeweb-style venue over FIX, prints flow stats every 5s
docker compose run --rm client        # raw cluster load test, prints latency percentiles
docker compose kill node0             # kill a member, run again: a new leader serves it
docker compose down
```

Any FIX 4.4 initiator can connect as `SenderCompID=TWSIM`, `TargetCompID=AERONFIRE`; the dictionary is
`src/main/resources/fix/aeronfire-fix44.xml`.

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

- [x] Pre-trade credit checks and client tiering.
- [x] Artio FIX gateway and Tradeweb-style venue simulator.
- [ ] Web blotter and terminal UI: RFQs, quotes, trades, positions, CS01, credit usage, cluster health.
- [ ] Failover test: in-process 3-member cluster, kill the leader mid-flow, assert no lost or duplicated trades.
- [ ] Gateway: reconnect to the new leader mid-flow, sweep stale RFQs, pool per-RFQ state.
- [ ] Client reconnects and resends on leader change (idempotent RFQ ids).
- [ ] Quote expiry via cluster timers instead of check-on-accept.
- [ ] Aeron Archive replay to rebuild valuations and CS01 as of a past log position.
- [ ] Risk egress: CS01 / DV01 by issuer on every trade.
- [ ] Latency report: HdrHistogram per hop, ZGC vs G1, bare metal vs cloud.
- [ ] JMH benchmarks for the pricer and codecs.
