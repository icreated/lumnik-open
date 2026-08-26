# Inbound rate limits

> Only the four LLM-backed endpoints are throttled, per tenant per 60-second fixed
> window — everything else is unlimited, and the counters are in-memory, single-node
> only.

The hub rate-limits its expensive endpoints — the ones that fan out to an LLM —
per tenant, in a fixed 60-second window. Everything else (CRUD, views, ingest) is
unlimited.

## What is limited

| Bucket | Endpoint | Default rpm |
|---|---|---|
| `view-generate` | `POST /api/views/generate` | **10** |
| `platform-chat` | `POST /api/platform/rag/chat` | 60 |
| `user-chat` | `POST /api/rag/chat` | 60 |
| `platform-search` | `POST /api/platform/rag/search` | 60 |

`view-generate` gets its own tighter limit because one call = one full LLM
manifest-drafting round. The other three fall back to `lumnik.ratelimit.default.rpm`
(60).

**Edition boundary:** `view-generate` lives in `lumnik-hub` and is limited on every
build. The other three buckets are declared on resources in `lumnik-rag`
(`RagChatResource`, `UserChatResource`, `RagSearchResource`), which the open edition
does not ship (`lumnik-rag` is a `runtime`-scope dependency only in the
`closed-extensions` profile, `lumnik-hub/pom.xml:369-379`) — on an open-edition build
those endpoints, and their limits, don't exist at all.

Counting is per **(bucket, tenant)**: one tenant hammering chat cannot exhaust
another tenant's budget, and the buckets are independent — burning `user-chat` does
not touch `platform-search`. Requests with no resolved tenant count under a shared
`system` key.

## The 429 contract

When a window is exhausted the request is refused before the resource runs:

```
HTTP/1.1 429 Too Many Requests
Retry-After: <seconds until the window resets>

{"error":"rate limit exceeded"}
```

`Retry-After` is exact, not advisory — the window is a fixed 60s clock, so waiting
that many seconds is guaranteed to land in a fresh window. `lm` surfaces this
verbatim — `rate limited (429): rate limit exceeded, retry in Ns` — rather than a
generic error.

## Tuning

Property form (per bucket, falling back to `default`):

```properties
lumnik.ratelimit.default.rpm=60
lumnik.ratelimit.view-generate.rpm=10
```

Env form for a deployed container — MicroProfile mapping, non-alphanumerics become
underscores:

```bash
LUMNIK_RATELIMIT_DEFAULT_RPM=120
LUMNIK_RATELIMIT_VIEW_GENERATE_RPM=20
LUMNIK_RATELIMIT_USER_CHAT_RPM=0    # 0 disables that bucket entirely
```

`0` disables — no counting, no 429s for that bucket. Restart the hub after changing
either form; limits are read per request from config, but env vars only reach the
process at boot.

## Observing 429s — attack or busy Monday?

Every refusal is now visible server-side, twice:

- **A WARN log line** (default log level shows it):
  `rate limit exceeded: bucket=user-chat tenant=42 rpm=60 retryAfter=17s`
- **A counter in `/q/metrics`**: `lumnik_rate_limited_total{bucket="…"}` — one series per
  bucket ([domain metrics](observability.md#domain-metrics)). A spike on one bucket from
  one tenant (read the WARN lines for the tenant) is your "attack or busy Monday"
  discriminator; wire an alert on its rate in your own Prometheus — none ships.

Both reset with the hub (in-memory counters, same [single-node caveat](#single-node-caveat)).

## Dev mode has NO limits

`%dev` sets both knobs to `0` — a local `mvn quarkus:dev` never returns 429, no
matter how hard you hit it. If you are trying to reproduce a customer's 429 locally,
you must set the properties back to a non-zero value; out of the box you can't.

## Single-node caveat

The counters live **in memory in the hub process**. Both shipped deploy paths
(selfhost compose, Helm chart) run a single hub replica, so today this is exact.
If you ever scale the hub to N replicas behind a balancer, each replica counts
independently — the effective limit becomes up to N× the configured rpm. That is a
known, deliberate trade (no Redis dependency for a single-replica product); revisit
it when a multi-replica deploy is real, not before.

Counters also reset on every hub restart — a restart forgives the current window.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| 429s in production you can't reproduce with `mvn quarkus:dev` | `%dev` sets both `default.rpm` and `view-generate.rpm` to `0` | Temporarily set the properties back to a non-zero value locally (see "Dev mode has NO limits"). |
| Changed `LUMNIK_RATELIMIT_*` in `.env`/deploy config but the limit didn't move | Env vars are read once, at process boot | Restart the hub. |
| `POST /api/rag/chat`, `/api/platform/rag/chat` or `/api/platform/rag/search` never return 429 no matter the load | Open edition: `lumnik-rag`, which owns those three buckets, isn't on the classpath — the endpoints don't exist | Expected on the open edition; only `view-generate` is enforced there (see the edition boundary note above). |
| A request seems to share its rate budget with unrelated callers | By design — any request with no resolved tenant counts under one shared `system` key | Not a bug; if that's surprising traffic, check why the tenant didn't resolve. |
| `429 Too Many Requests` shows up in a connector run's logs, not from an API/chat client | That's the OTHER 429 — a vendor system throttling lumnik's outbound calls, unrelated to this page | Tune `default_rate_limit` in the connector YAML; see below. |

## Not to be confused: the OTHER 429

The quickstart's troubleshooting table mentions `429 Too Many Requests` **from the
vendor** — that one is a different animal. It is the *source system* (Stripe, Siebel,
whatever your REST connector pulls from) throttling **lumnik's outbound calls**
during a connector run; you tune it with `default_rate_limit` in the connector YAML,
not with any `lumnik.ratelimit.*` knob. This page's 429 is the hub throttling
**inbound API callers** (chat, search, view generation). Same status code, opposite
directions: one protects the vendor from lumnik, the other protects lumnik from its
own users.
