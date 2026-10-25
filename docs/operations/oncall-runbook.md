# On-call runbook — 2 a.m., by symptom

> What is safe to restart, what each restart costs, and the exact commands.

**Scope.** The self-host compose stack. SSH to the host and run every command **from the repo
root** (where `docker-compose.selfhost.yml` and `.env` live). On Kubernetes the costs below still
hold; the gestures are your cluster's (`kubectl rollout restart …`).

!!! danger "At 2 a.m., `restart` — never a bare `docker compose up`"
    `docker compose up -d` on the base file alone recreates `hub` and `keycloak` **without the overlays**.
    On a TLS-proxy install, the hub then refuses tokens from the https origin.
    `restart <svc>` touches one container and keeps its settings.
    `up.sh` keeps the overlays, but it pulls the hub image: unless `LUMNIK_VERSION` pins a release,
    that is an upgrade. Leave it for the morning.

## Step 0 — health before logs

`lm` has no health command; ask the hub directly:

```
$ curl -s http://localhost:8080/q/health
{
    "status": "UP",
    "checks": [
        {
            "name": "Database connections health check",
            "status": "UP",
            "data": {
                "readonly": "UP",
                "<default>": "UP",
                "admin": "UP"
            }
        }
    ]
}
```

| What you see | What it means |
|---|---|
| `"status": "DOWN"` | a database connection is down; `data` names which one |
| no answer at all during a boot | the hub is still starting — migrations run first (`Migrating schema …` lines in the hub log) |
| `UP`, but chat or ask fails | `/q/health` checks only the database: a down Ollama or Keycloak still shows `UP` |

Then: [which log to read, by symptom](../recipes/logs.md).

## "The chat is down" — the short path

1. `/q/health` (above) — rules out the database.
2. `./deploy/selfhost/logs.sh hub` — the [log recipes](../recipes/logs.md) have the jq one-liners.
3. [The ask and the model](../recipes/troubleshooting.md#the-ask-and-the-model): the symptom
   table, with the fix for each.

## The nightly login mystery, pre-solved

Logins fail for ~15 seconds at a fixed night hour: your [backup cron](backup-restore.md) is
stopping Keycloak to copy its store (the example schedules it at 03:10). Issued tokens keep
working. Check your crontab's hour before opening an incident.

## The restart matrix — cost and command

| Container | Safe? | Command (repo root) | What it costs |
|---|---|---|---|
| `hub` | yes | `docker compose -f docker-compose.selfhost.yml restart hub` | see below |
| `keycloak` | yes, briefly | `docker compose -f docker-compose.selfhost.yml restart keycloak` | **New logins fail during the window; issued tokens keep working.** The store survives on its volume. |
| `postgres` | be gentle | `docker compose -f docker-compose.selfhost.yml restart postgres` | A full outage while it lasts. Expect a failed request or two right after. Interrupted runs resume on their cursor. |
| Ollama (host) | yes | Linux: `systemctl restart ollama` · macOS/Windows: the Ollama app | The first ask after the restart is a cold load (tens of seconds). `OLLAMA_KEEP_ALIVE=-1` keeps the model resident ([the trap list](../quickstart/prerequisites.md)). |
| `caddy` (proxy) | yes | `docker compose -f docker-compose.selfhost.yml -f docker-compose.tls.yml restart caddy` | A blip on the https origin. Certificates and the internal CA survive on the `tls_caddy_data` volume (`docker volume ls` shows `<project>_tls_caddy_data`). |

What a `hub` restart costs:

- **Rate-limit counters** reset ([rate limits](rate-limits.md)) — harmless.
- **A jdbc or CSV run in flight** stops and resumes on its cursor at the next trigger
  ([how jdbc cursors resume](../connectors/jdbc.md#resume-restart-reset)).
- **A REST run in flight** restarts its pagination from page one.
- **The boot window**: the compose healthcheck allows up to ~210 s before `unhealthy`; a restart
  on an already-migrated database is much faster.

## After a hard kill: the run marks itself failed

- **What you see:** a run killed mid-flight stays `Running`, and its connector's schedule waits.
- **How long:** at most a minute after the hub is back: it marks the run `Failed`.
- **What to do:** nothing. The connector resumes on its cursor at its next scheduled run, or now
  with `lm connector run <name>`.

`lm run get` prints the reason:

```
$ lm run get ff03ad75
ID        CONNECTOR      ENDPOINT  STATUS  IN   OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
ff03ad75  acme-desk-erp  default   Failed  500  500  0        0             2026-10-19 01:48  2026-10-19 01:52
✖ run failed: reaped: the hub process running it restarted or died while it was in flight
```

A reaped run's `ENDED` is when the hub noticed, not when the run died: its duration includes the outage.

## What waits for morning

- Anything touching the database's disk — nothing purges the mirror ([storage](sizing.md#storage-the-sizing-question-this-page-does-not-answer)).
- Credential rotation.
- Any `lm connector delete` — it purges the connector's data.
- Any `up`-shaped gesture — see the red box above.
