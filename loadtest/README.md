# Load tests — sizing the hub with k6

Measure what a lumnik deployment can serve, find the knee of the curve, and turn it into an
infrastructure sizing decision. Scenarios are [k6](https://k6.io) scripts; nothing to install —
the runner uses the `grafana/k6` Docker image.

## Quickstart (against a running self-host stack)

```bash
./loadtest/run.sh smoke.js                 # prove the harness: auth + routes, ~10 s
MAX_VUS=25 ./loadtest/run.sh read-mix.js   # the sizing ramp, ~2.5 min
```

`run.sh` mints the Bearer token as user `integrator`, with the password from the repo-root `.env`.
For another stack, set `K6_USER` / `K6_PASSWORD` / `KC_URL` / `BASE_URL`.

## What is measured — and what is not

**Measured:** the hub's read surface, weighted like integrator usage — `/api/platform/version`,
`/api/platform/connectors`, `/api/platform/runs`, `/api/views`, `/api/workflows`, `/api/inbox`,
`/api/entities/conflicts`. Every one answers `200` even on an empty stack, and every request is
authenticated and tenant-scoped.

**Not measured:**

- **`ask` / chat / RAG search** — the model dominates their cost. Size the model on its own hardware.
- **Ingestion** — a scheduled batch, not concurrent users; its cost is in the run reports.

## The sizing method

1. Run `smoke.js` first, twice (the first run warms the JVM). A failing smoke means the harness
   is wrong, not the hub.
2. Ramp `read-mix.js` at increasing `MAX_VUS` (25 → 50 → 100 …) until p95 bends sharply upward
   or `http_req_failed` leaves zero. The last clean step is this deployment's capacity.
3. Watch `docker stats` on the hub and PostgreSQL during the ramp. **The knee is usually the
   database**: pool saturation shows as latency, not errors.
4. Record one line per run in the sizing table:

| date | hardware (CPU/RAM) | stack | MAX_VUS | p95 worst endpoint | failed | verdict |
|---|---|---|---|---|---|---|
| 2026-08-17 | M1 Max / 64 GB | selfhost compose (image 2026-07-12, `ONLY` 5 surfaces¹) | 25 | runs: 210 ms | 0.00% | clean |
| 2026-08-17 | M1 Max / 64 GB | selfhost compose, full 7-surface mix | 25 | connectors: 50 ms | 0.00% | clean — ~51 rps |
| 2026-08-17 | M1 Max / 64 GB | selfhost compose, full 7-surface mix | 50 | runs: 50 ms | 0.00% | clean — ~101 rps, p95 flat vs 25 VUs |
| 2026-08-18 | VPS, 4 vCPU / 16 GB / NVMe | selfhost + TLS façade, via https² | 25 | runs: 10.6 ms | 0.00% | clean — ~56 rps |
| 2026-08-18 | VPS, 4 vCPU / 16 GB / NVMe | selfhost + TLS façade, via https² | 50 | connectors: 10.9 ms | 0.00% | clean — ~112 rps, p95 flat vs 25 VUs |

¹ that image did not have the `workflows` surface, hence `ONLY`.

² through the full public path (Caddy, TLS, the façade issuer), k6 running on the VM itself:
client network excluded, TLS included.

## Search (PostgreSQL provider)

With the PostgreSQL Search provider, the index shares the database with ingestion. This scenario
measures query latency per matching mode, ingestion speed, index catch-up time, and what each does
to the other. It runs on its own throwaway stack (ports 18080/18180, own volumes, a hub built with
`-Psearch`):

```bash
bash loadtest/search/stack.sh up                              # builds the hub with the provider the first time (slow); REBUILD=1 forces a rebuild
MIN_FREE_MB=8000 bash loadtest/search-sizing.sh --tiers 10000 # writes loadtest/search/results/<stamp>/summary.md
bash loadtest/search/stack.sh down                            # removes the stack and its volumes
```

The script refuses a tier when the Docker VM has less than `MIN_FREE_MB` free (default 15000); the
image needs about 0.6 GB plus the database.

**What a tier does.** Four entities (`sz_exact`, `sz_text`, `sz_fuzzy`, `sz_prefix`) carry the
same generated rows and differ only in their matching mode. `N` is rows **per entity**. Three
phases run from the same starting point:

| phase | what runs | what it answers |
|---|---|---|
| A | the query mix at 20 requests/s for 60 s | latency with nothing else running |
| B | ingestion of N/10 more rows per entity, no search load | ingestion speed and catch-up on their own |
| C | the same ingestion while the query mix keeps its rate, until the index has caught up | what sharing the database costs both sides |

- B and C each start from the same saved copy of the database, so the comparison is contention,
  not extra volume.
- The corpus comes from a fixed seed: 70 % canonical values, 15 % one-letter typos,
  10 % abbreviations, 5 % phonetic variants.
- **Caught up** means: for all four indexes, lag 0, document count equal to the rows ingested,
  and that count fresh. Lag 0 alone proves nothing — it reads 0 before projection starts.
- Catch-up pace is set by `LUMNIK_SEARCH_PROJECTION_CATCHUP_BUDGET` (default 30 s per minute; see
  [How fast an index catches up](../search/search-indexes.md#how-fast-an-index-catches-up)).

### Observed on one machine — an envelope, not a capacity

MacBook Pro M1 Max, 64 GB; Docker Desktop VM with 10 CPUs and 7.6 GB; hub and PostgreSQL in
containers next to Keycloak; 2026-10-01. Rate 20 requests/s, catch-up budget 30 s. Each cell is
**run 1 / run 2**, each on a fresh stack.

**N = 10 000 rows per entity** (40 000 in total, plus one seed row each):

| phase | exact p95 ms | text p95 ms | fuzzy p95 ms | prefix p95 ms | ingestion, rows/s (4 entities) | catch-up s | max lag during the load, s | max lag to convergence, s |
|---|---|---|---|---|---|---|---|---|
| A — search alone | 61 / 71 | 34 / 39 | 60 / 77 | 35 / 39 | — | — | — | — |
| B — ingestion alone | — | — | — | — | 5 063 / 4 938 | 40 / 38 | 0 / 0 | 38 / 32 |
| C — search during ingestion | 70 / 72 | 46 / 41 | 70 / 79 | 44 / 39 | 3 540 / 4 444 | 52 / 47 | 0 / 0 | 47 / 41 |

**N = 50 000 rows per entity** (200 000 in total, plus one seed row each):

| phase | exact p95 ms | text p95 ms | fuzzy p95 ms | prefix p95 ms | ingestion, rows/s (4 entities) | catch-up s | max lag during the load, s | max lag to convergence, s |
|---|---|---|---|---|---|---|---|---|
| A — search alone | 121 / 108 | 94 / 87 | 118 / 108 | 140 / 114 | — | — | — | — |
| B — ingestion alone | — | — | — | — | 13 699 / 18 018 | 54 / 66 | 0 / 0 | 38 / 43 |
| C — search during ingestion | **3 653 / 349** | **2 222 / 195** | **4 213 / 381** | **2 031 / 195** | 3 311 / 6 623 | 42 / 57 | 0 / 1 | 44 / 58 |

| Finding | Figures |
|---|---|
| Search alone holds a 500 ms budget | p95 87–140 ms at 50 000 rows (p99 ≤ 188 ms); runs agree within 25 % |
| Search during ingestion at 50 000 rows is **not settled** | run 2: 195–381 ms; run 1: 2.0–4.2 s (p50 ~100 ms in both) — no figure is claimed |
| Contention at 10 000 rows is small | p95 ≤ 79 ms; ingestion 10–30 % slower than alone |
| Catch-up | 1 000-row delta: 38–52 s; 5 000-row delta: 42–66 s, with or without search load |
| Disk after phase B / C | 10 000 rows: 79–81 / 51–52 MB; 50 000 rows: 271–273 / 195–199 MB (from 12 MB) |

- Ingestion lasts 0.8–6.0 s, timed from the hub's run reports: its rows/s is the least precise
  figure.
- Lag during the load is 0 by construction (ingestion ends before projection drains); the
  figure that moves is the lag to convergence.
- **Not measured:** 200 000 or 1 million rows per entity, the cluster provider, other hardware.

### Deciding for your own installation

Replay this scenario on your hardware at the volume and rate you expect, then apply
[Choosing a provider](../search/search-indexes.md#choosing-a-provider). Rows per tenant is not a
trigger: decide on the contention you observe and on your latency and freshness budgets.

## Caveats

- **Localhost numbers size only localhost.** Size on the target hardware, over the users' network.
- **Tokens from the bundled realm live 1 hour** (`accessTokenLifespan: 3600`): enough for every
  scenario here except a long Search tier. The Search orchestrator renews its own token every
  25 minutes (`SZ_TOKEN_REFRESH_S` lowers it); phase C's k6 load does not, so a catch-up over an
  hour ends in 401s and the phase is reported failed. No token-refresh loop ships for other soak tests.
- **Thresholds** (`P95_MS`, default 500 ms) are regression gates, not SLOs; tune them per environment.
- **`ONLY=name,name`** narrows the mix explicitly; the default is always the full mix, so a missing
  endpoint goes red.

## Files

| file | role |
|---|---|
| `run.sh` | token minting + dockerised k6 invocation |
| `k6/lib.js` | endpoints, weights, auth params |
| `k6/smoke.js` | 1-VU harness check, CI-able |
| `k6/read-mix.js` | the ramping sizing scenario |
| `k6/search-mix.js` | the Search query mix at a constant arrival rate, per mode |
| `search-sizing.sh` | the Search sizing orchestrator (phases A, B and C per tier) |
| `search/stack.sh` | the throwaway `-Psearch` stack (`up`, `down`, `token`, `psql`, `pgdump`/`pgrestore`) |
| `search/snapshot.sh` | save and restore the stack's database, with a counts proof |
| `search/gen_corpus.py` | the deterministic corpus and query generator |
