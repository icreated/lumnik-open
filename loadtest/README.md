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

## Honest caveats

- **Localhost numbers size nothing but localhost.** The sizing that counts is
  run on the target hardware, over the network the users will use.
- Access tokens from the bundled realm live 1 hour (`accessTokenLifespan: 3600`) —
  ample for every scenario here. A soak test outliving the token needs a
  token-refresh loop (not built yet — build it when a soak test is actually
  needed), and a stricter IdP shortens the budget.
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
