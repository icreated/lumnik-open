# Load tests — sizing the hub with k6

Measure what a lumnik deployment can serve, find the knee of the curve, and
turn that into an infrastructure sizing decision. Scenarios are
[k6](https://k6.io) scripts (JavaScript); no local install needed — the runner
uses the `grafana/k6` Docker image.

## Quickstart (against a running self-host stack)

```bash
./loadtest/run.sh smoke.js                 # prove the harness: auth + routes, ~10 s
MAX_VUS=25 ./loadtest/run.sh read-mix.js   # the sizing ramp, ~2.5 min
```

`run.sh` mints the Bearer token from the host (user `integrator`, password read
from the repo-root `.env`), so the token's issuer matches what the hub trusts.
Override with `K6_USER` / `K6_PASSWORD` / `KC_URL` / `BASE_URL` for another stack.

## What is measured — and what deliberately is not

**Measured:** the hub's own read surface, weighted like real integrator usage —
`/api/platform/version|connectors|runs`, `/api/views`, `/api/workflows`,
`/api/inbox`, `/api/entities`. All list endpoints, all guaranteed `200` on any
stack, even empty. Every request is Bearer-authenticated and RLS-scoped, so the
measurement includes the real auth + tenant-isolation cost.

**Not measured (v1, by design):**

- **`ask` / chat / RAG search** — those calls are dominated by the model
  (Ollama or a remote provider), so a load test would size *the model*, not the
  hub. Size model capacity separately, on the model's own hardware.
- **Ingestion** — a scheduled batch on virtual threads, not a concurrent user
  load; its cost is per-run, measured by run reports, not by VUs.

## The sizing method

1. `smoke.js` first — a failing smoke means the harness is wrong, not the hub.
   Run it twice: the first run also warms the JVM.
2. Ramp `read-mix.js` at increasing `MAX_VUS` (25 → 50 → 100 …) until either
   p95 bends sharply upward or `http_req_failed` leaves zero. The last clean
   step is the capacity of this deployment.
3. Watch the other half: `docker stats` on the hub and Postgres during the
   ramp. **The knee is usually the database** — connection pool saturation
   shows up as latency, not as errors.
4. Record one line per run in the sizing table:

| date | hardware (CPU/RAM) | stack | MAX_VUS | p95 worst endpoint | failed | verdict |
|---|---|---|---|---|---|---|
| 2026-08-17 | M1 Max / 64 GB | selfhost compose (image 2026-07-12, `ONLY` 5 surfaces¹) | 25 | runs: 210 ms | 0.00% | clean |
| 2026-08-17 | M1 Max / 64 GB | selfhost compose, current main, full 7-surface mix | 25 | connectors: 50 ms | 0.00% | clean — ~51 rps |
| 2026-08-17 | M1 Max / 64 GB | selfhost compose, current main, full 7-surface mix | 50 | runs: 50 ms | 0.00% | clean — ~101 rps, p95 flat vs 25 VUs, no knee in sight |
| 2026-08-18 | Hostinger KVM 4 VPS (4 vCPU EPYC / 16 GB / NVMe) | selfhost + TLS façade, via https² | 25 | runs: 10.6 ms | 0.00% | clean — ~56 rps |
| 2026-08-18 | Hostinger KVM 4 VPS (4 vCPU EPYC / 16 GB / NVMe) | selfhost + TLS façade, via https² | 50 | connectors: 10.9 ms | 0.00% | clean — ~112 rps, p95 flat vs 25 VUs, no knee |

¹ that image predated the `workflows` surface; after `up.sh --build` on current main
the full mix runs green. Note the 210 ms → 50 ms drop between the July image and
current main — the August performance campaign, visible in one table.

² measured through the full public path — Caddy, TLS, the façade issuer — with k6
running on the VM itself (so client network excluded, TLS stack included). A ~$15/mo
VPS holds p95 ≈ 11 ms at 112 rps on the authenticated, RLS-scoped read mix: for a
tester-scale or small-company deployment, the hub is not the bottleneck.

## Search (PostgreSQL provider)

With the PostgreSQL Search provider, the index lives in the database that also takes your
ingestion. This scenario measures what sharing costs: query latency per matching mode, how fast
rows are ingested, how long the index takes to catch up, and what each does to the other.

It runs on its own throwaway stack (separate ports 18080/18180, separate volumes, a hub built
with `-Psearch`), never against a stack you use:

```bash
bash loadtest/search/stack.sh up                              # builds the hub with the provider the first time (slow); REBUILD=1 forces a rebuild
MIN_FREE_MB=8000 bash loadtest/search-sizing.sh --tiers 10000 # writes loadtest/search/results/<stamp>/summary.md
bash loadtest/search/stack.sh down                            # removes the stack and its volumes
```

The Docker disk needs room for the image (about 0.6 GB) and the database; the script refuses a
tier when the Docker VM has less than `MIN_FREE_MB` free (default 15000) rather than fill it.

**What a tier does.** Four equivalent entities (`sz_exact`, `sz_text`, `sz_fuzzy`, `sz_prefix`)
carry the same generated rows and differ only in how their name is matched, so a p95 belongs to
one mode. `N` is rows **per entity**. Three phases run from the same starting point:

| phase | what runs | what it answers |
|---|---|---|
| A | the query mix at a fixed arrival rate (20 requests/s for 60 s) | latency with nothing else running |
| B | ingestion of N/10 more rows per entity, no search load | ingestion speed and catch-up on their own |
| C | the same kind of ingestion (other keys, same size and distribution) while the query mix keeps its rate, until the index has caught up | what sharing the database costs both sides |

B and C each start from the **same saved copy** of the database at N, so the comparison is
contention and not extra volume. Queries are identical at every tier. The corpus is generated
from a fixed seed: 70 % canonical values, 15 % one-letter typos, 10 % abbreviations, 5 % phonetic
variants, with words taken from the project's own test corpus. It is a realistic vocabulary,
not your data.

**What "caught up" means.** The index is caught up when, for all four indexes at once, its lag
is 0 **and** its document count equals the rows ingested **and** that count is fresh. A lag of 0
on its own proves nothing: right after a run ends, nothing has been projected yet and the lag
already reads 0. The hub applies changes in batches of at most 500 documents, one attempt per index
on each scheduler tick (nominally once per minute) and, while a 30-second budget per minute lasts, further batches in turn (see
[How fast an index catches up](../search/search-indexes.md#how-fast-an-index-catches-up)). **The
1 000-row delta of a 10 000-row tier was caught up in 38 to 52 seconds, and the 5 000-row delta of a
50 000-row tier in 42 to 66 seconds**, one minute-tick included. The catch-up of a 200 000-row base
is not measured here: it depends on the budget, set by `LUMNIK_SEARCH_PROJECTION_CATCHUP_BUDGET`
(with `0s`, no additional batches run during a tick).

### Observed on one machine — an envelope, not a capacity

MacBook Pro, Apple M1 Max, 64 GB; Docker Desktop VM with 10 CPUs and 7.6 GB of memory; hub and
PostgreSQL in containers next to Keycloak; 2026-10-01; the hub built from the commit stamped in
each run's `version.txt`. Same recipe, rate (20 requests/s) and catch-up budget (30 s) for both
tiers. Each cell shows **run 1 / run 2**: two complete runs, each on a freshly created stack.

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

- **Search alone is well inside a 500 ms budget at both volumes.** At 50 000 rows per entity the
  p95 of every mode is 87 to 140 ms (and p99 at most 188 ms) with nothing else running; the two
  runs agree within 25 %. Before the fix of #758, the same protocol at 50 000 rows gave 5 to 9
  seconds in this phase (the provider's query scanned the whole projection and used none of its
  indexes), so this is the figure that changed, by about 40 to 75 times.
- **Search while the index is being fed at 50 000 rows is not settled.** Run 2 stayed at 195 to
  381 ms; run 1 reached 2.0 to 4.2 seconds at the p95 (p50 about 100 ms in both), and its
  ingestion also took twice as long (6.0 s against 3.0 s). The two runs differ by a factor of ten
  and nothing was instrumented to say why: machine noise during run 1 is one possibility, a
  real contention effect another. Two runs cannot tell which is the outlier, so **no figure for
  this phase is claimed**; the honest range is 195 ms to 4.2 s.
- **At 10 000 rows, contention is small.** While ingestion and the index catch-up ran, the p95 of
  every mode stayed at or under 79 ms, up to 35 % above phase A on a single mode in run 1 and about
  5 % or less in run 2. Ingestion slowed by 10 to 30 % (5 063 → 3 540 and 4 938 → 4 444 rows/s).
- **Ingestion is timed from the hub's own run reports** (first run start to last run end) and lasts
  between 0.8 and 6.0 seconds, so its rows/s is the least precise number of the tables; the comparison between
  B and C uses the same window on both sides.
- **Catch-up is not the problem.** The 5 000-row delta of the 50 000-row tier was caught up in 42
  to 66 seconds in every run, with or without search load. Whether the 30-second budget was fully
  used in each minute was not instrumented; the convergence times are the evidence that it was
  enough to empty these backlogs.
- **Disk.** 10 000 rows: 79–81 MB after phase B, 51–52 MB after phase C (it starts from a restored,
  compact copy), from 12 MB. 50 000 rows: 271–273 MB and 195–199 MB.
- **Lag during the load is 0 by construction**: the ingestion ends before the projection starts
  draining, so the lag only appears afterwards. The figure that moves is the second one, the
  maximum over the whole catch-up (32 to 58 seconds).

### Conclusion of this measurement

On this machine, with the provider as it is after #758, **search alone holds the 500 ms budget at 10 000
and at 50 000 rows per entity at 20 requests/s** (p95 at most 140 ms). What is **not** established
is the behaviour of search *while* a 5 000-row delta is being ingested and projected at 50 000 rows:
one run says 195 to 381 ms, the other 2 to 4 seconds. That needs more runs, and
instrumentation of what the database does during the window, before it can be called a capacity.

**200 000 rows per entity was not run.** The 50 000-row tier no longer saturates by itself, so it
is now a meaningful next step; it takes hours and is left to a decision once the phase C spread
above is understood. Not measured, and therefore not claimed: 200 000 rows, 1 million rows, the
cluster provider, any other hardware, and any disk or network other than this laptop's.

### Deciding for your own installation

Replay this scenario on your hardware at the volume and rate you expect, then apply the rule in
[Choosing a provider](../search/search-indexes.md#choosing-a-provider). Rows per tenant is a column
of the table above, not a trigger: the decision depends on the contention you observe and on your
own latency and freshness budgets.

## Honest caveats

- **Localhost numbers size nothing but localhost.** The sizing that counts is
  run on the target hardware, over the network the users will use.
- Access tokens from the bundled realm live 1 hour (`accessTokenLifespan: 3600`) —
  enough for every k6 scenario here except a long Search sizing tier. The Search orchestrator
  renews its own token while it waits for the index to catch up (every 25 minutes, so a catch-up
  of several hours still converges; `SZ_TOKEN_REFRESH_S` lowers the interval for a
  stricter IdP). The k6 load of phase C uses the token minted when it starts: a catch-up longer than
  about an hour (possible at a small budget on a large tier) would turn into 401s, and the
  orchestrator then reports the phase as failed instead of publishing it. Any other soak test
  outliving the token needs a token-refresh loop (not built yet — build it when a soak test is
  actually needed), and a stricter IdP shortens the budget.
- Thresholds (`P95_MS`, default 500 ms) are regression gates, not SLOs. Tune
  them per environment; a threshold that cannot fail protects nothing.
- `ONLY=name,name` narrows the mix **explicitly** — to isolate one surface, or
  against an older build that predates a surface. The default is always the
  full mix: a missing endpoint must go red, never silently vanish.

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
