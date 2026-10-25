# Inbound rate limits

> Five endpoints are throttled, per tenant, in a fixed 60-second window. Everything else is
> unlimited. The counters live in the hub process.

## What is limited

| Bucket | Endpoint | Used by | Default rpm | Edition |
|---|---|---|---|---|
| `view-generate` | `POST /api/views/generate` | `lm view generate` | **10** | all |
| `search` | `GET /api/search` | `lm search query`, the PWA Search tab | 60 | all |
| `platform-chat` | `POST /api/platform/rag/chat` | `lm chat`, `lm ask` | 60 | Pro |
| `user-chat` | `POST /api/rag/chat` | the PWA chat | 60 | Pro |
| `platform-search` | `POST /api/platform/rag/search` | `lm rag search` | 60 | Pro |

- A bucket without its own setting uses `lumnik.ratelimit.default.rpm` (60).
- On the open edition the three Pro endpoints do not exist, and neither do their limits.
- Counting is per **(bucket, tenant)**: one tenant cannot exhaust another's budget, and buckets
  are independent. Requests with no resolved tenant share one `system` budget.

## The 429 contract

When a window is exhausted the request is refused before it runs:

```
HTTP/1.1 429 Too Many Requests
Retry-After: <seconds until the window resets>

{"error":"rate limit exceeded"}
```

Waiting `Retry-After` seconds always lands in a fresh window. `lm` prints:

```
Error: rate limited (429): rate limit exceeded, retry in 17s
```

## Tuning

Property form (per bucket, falling back to `default`):

```properties
lumnik.ratelimit.default.rpm=60
lumnik.ratelimit.view-generate.rpm=10
```

Environment form (non-alphanumerics become underscores):

```bash
LUMNIK_RATELIMIT_DEFAULT_RPM=120
LUMNIK_RATELIMIT_VIEW_GENERATE_RPM=20
LUMNIK_RATELIMIT_USER_CHAT_RPM=0    # 0 disables that bucket
```

`0` disables a bucket: no counting, no 429. A misspelt bucket name is ignored.

**Self-host.** Set the variables in the root `.env`, then recreate the hub alone — it reads them at
startup:

```bash
docker compose -f docker-compose.selfhost.yml up -d hub
```

- Name every overlay the stack runs with (`-f docker-compose.tls.yml`…). The hub lists them:
  `docker inspect $(docker compose -f docker-compose.selfhost.yml ps -q hub) --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'`.
  With the TLS proxy, `LUMNIK_TLS_HOST` must be set too, as when you started it.
- `up.sh` keeps the overlays by itself, but pulls the hub image: pin `LUMNIK_VERSION` in `.env`
  first, or the change becomes an upgrade.

Before that, check what the hub container will receive. With `LUMNIK_RATELIMIT_USER_CHAT_RPM=0` in `.env`:

```
$ docker compose -f docker-compose.selfhost.yml config hub | grep RATELIMIT
      LUMNIK_RATELIMIT_DEFAULT_RPM: "60"
      LUMNIK_RATELIMIT_PLATFORM_CHAT_RPM: ""
      LUMNIK_RATELIMIT_PLATFORM_SEARCH_RPM: ""
      LUMNIK_RATELIMIT_SEARCH_RPM: ""
      LUMNIK_RATELIMIT_USER_CHAT_RPM: "0"
      LUMNIK_RATELIMIT_VIEW_GENERATE_RPM: "10"
```

An empty value is the same as unset: that bucket uses `default`.

**Helm (cloud edition).** The chart has no value per bucket; pass them through `hub.extraEnv`, then restart the hub.
On the kind cluster `up.sh` built:

```bash
helm --kube-context kind-lumnik upgrade lumnik deploy/helm/lumnik --reuse-values \
  --set 'hub.extraEnv[0].name=LUMNIK_RATELIMIT_DEFAULT_RPM' --set-string 'hub.extraEnv[0].value=120'
kubectl --context kind-lumnik rollout restart deployment/lumnik-hub
```

`--set` on a list replaces the whole list: name every `hub.extraEnv` entry you want to keep.

## Observing 429s

Every refusal shows twice on the hub side:

- **A WARN log line:**
  `rate limit exceeded: bucket=user-chat tenant=42 rpm=60 retryAfter=17s`
- **A counter:** `lumnik_rate_limited_total{bucket="…"}` in `/q/metrics`
  ([domain metrics](observability.md#domain-metrics)). No alert ships; wire one on its rate.

A spike on one bucket from one tenant (the WARN lines name it) tells an attack from a busy
Monday. Both reset when the hub restarts.

## Single-node caveat

- **Counters are per hub process**, in memory. Both shipped deploy paths run one hub replica, so
  the limits are exact there.
- **N replicas behind a balancer** count independently: the effective limit is up to N × the
  configured rpm.
- **A hub restart** resets every window.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Set `LUMNIK_RATELIMIT_*` in `.env`, the limit did not move | the hub reads it at startup only, or the bucket name is misspelt (an unknown bucket is ignored) | Recreate the hub (`up -d hub`) and check the name against the bucket list (see [Tuning](#tuning)). |
| The chat or RAG search endpoints never return 429 | open edition: they do not exist | Expected; `view-generate` and `search` are limited there. |
| A request seems to share its budget with unrelated callers | requests with no resolved tenant share the `system` budget | Check why the tenant did not resolve. |
| `429 Too Many Requests` in a connector run, not from an API client | the **source system** throttling lumnik's outbound calls | Lower `default_rate_limit` in the connector YAML ([REST](../connectors/rest.md)). |
