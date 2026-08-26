# On-call runbook — 2 a.m., by symptom

> What is safe to restart, what each restart costs, and the exact commands — assembled
> once, instead of across three pages at 2 a.m.

**Scope and access.** This page is for the **self-host compose stack**. SSH to the host
that runs it; every command below runs **from the repo root** there (that is where
`docker-compose.selfhost.yml` and `.env` live). On Kubernetes, the costs column still
holds but the gestures are your cluster's (`kubectl rollout restart …`).

!!! danger "At 2 a.m., `restart` — never `up`"
    Re-running `up.sh`, or `docker compose up -d` with only the base file, **re-composes
    the stack without the TLS overlay** — caddy vanishes and every device loses the
    https origin ([the overlay trap](../deploy.md#one-https-origin-the-tls-facade)).
    `docker compose -f docker-compose.selfhost.yml restart <svc>` touches one container
    and cannot un-stack anything.

## Step 0 — health before logs

```bash
curl -s http://localhost:8080/q/health
```

then the [symptom → log routing tree](../recipes/logs.md). For the chat specifically,
know this before hunting for a dashboard: **no chat/ask metric exists**
([what /q/metrics does not carry](observability.md)) — the toolkit is `/q/health`, the
logs, and the [ask-and-model symptom table](../recipes/troubleshooting.md).

## "The chat is down" — the short path

1. `/q/health` — a DOWN names the sick service; **no response at all** during a boot
   means Flyway is still migrating (that *is* the signal — there is no named check).
2. `./deploy/selfhost/logs.sh hub` — the [log recipes](../recipes/logs.md) have the jq
   one-liners.
3. The [ask-and-model table](../recipes/troubleshooting.md): an opaque error after a
   long wait = the model never loaded; nothing at all = Ollama unreachable (on Linux,
   the bridge trap); `429` = the hub's own rate limit.

## The nightly login mystery, pre-solved

Logins failing for ~15 seconds *at a fixed night hour*: that is your
[backup cron](backup-restore.md) briefly stopping Keycloak to copy its store (the docs'
example schedules it at 03:10) — expected; already-issued tokens keep working
throughout. Check your crontab's hour before opening an incident.

## The restart matrix — cost and command

| Container | Safe? | Command (repo root) | What it costs |
|---|---|---|---|
| `hub` | yes | `docker compose -f docker-compose.selfhost.yml restart hub` | In-memory [rate-limit counters](rate-limits.md) reset (harmless). A jdbc/CSV run in flight is interrupted and **resumes on its cursor** at the next trigger ([watermark resume](../connectors/jdbc.md#watermark-resume-composite-cursor)); a REST run restarts its pagination from page one — no duplicates either way (content-hash / unique-key upserts). The compose healthcheck allows up to ~210 s before flagging unhealthy; a restart on an already-migrated DB is typically much faster. |
| `keycloak` | yes, briefly | `docker compose -f docker-compose.selfhost.yml restart keycloak` | **New logins fail during the window; already-issued tokens keep working.** The store survives on its volume. |
| `postgres` | be gentle | `docker compose -f docker-compose.selfhost.yml restart postgres` | A full outage while it lasts. The hub's pools recover, but expect a failed request or two right after (no connection validation is configured). Interrupted runs resume on their cursor afterwards. |
| Ollama (host) | yes | Linux: `systemctl restart ollama` · macOS/Windows: the Ollama app | The model unloads: the **first ask after restart is a cold load** (tens of seconds). `OLLAMA_KEEP_ALIVE=-1` keeps it resident ([the trap list](../quickstart/prerequisites.md)). |
| `caddy` (façade) | yes | `docker compose -f docker-compose.selfhost.yml -f docker-compose.tls.yml restart caddy` | A blip on the HTTPS origin. Its state (ACME certs, the internal CA root your devices trust) survives on the `caddy_data` volume — which is exactly why the restart is a blip. |

!!! note "After a hard kill: the zombie run reaps itself at the next start"
    A run killed mid-flight leaves its row at `Running`, and the scheduler skips any
    connector that has one. The hub's startup reaper fails those rows
    (`error_message: reaped at startup…`) and logs a WARN with the count — so a crash
    costs at most one restart, never a silenced schedule. The failed run's connector
    resumes on its cursor at the next trigger.

## What waits for morning

Anything touching the database's disk (see [storage growth](sizing.md) — nothing purges
the mirror), credential rotation, any `lm connector delete` (it purges data by design),
and any `up`-shaped gesture — see the red box above.
