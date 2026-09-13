# Observability

> Health probes, Prometheus metrics and OpenTelemetry tracing are wired in and live on
> every boot — no dashboards, alert rules, or RAG/chat metrics ship with it.

The hub ships with health probes, Prometheus metrics and OpenTelemetry tracing wired
in (`quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`,
`quarkus-opentelemetry`). Nothing to install — the endpoints are live on every boot.

## What's exposed out of the box

| Endpoint | What it answers | Auth |
|---|---|---|
| `GET /q/health` | overall UP/DOWN, all checks | none |
| `GET /q/health/live` | "the process is alive" — restart me if not | none |
| `GET /q/health/ready` | "I can serve traffic" (DB reachable, migrations done) — the Helm chart's readiness probe. The selfhost compose healthcheck (what `up.sh` waits on) watches `/q/health/live` instead; same boot window in practice, since the hub serves neither until Flyway finishes | none |
| `GET /q/metrics` | Prometheus text format: JVM, HTTP server, DB pool + the domain metrics below | none |
| `GET /api/platform/health` | application-level: `{"platform":"ok","modules":{...}}` — one entry per installed module with its version | `lm_integrator` |

The `/q/*` endpoints are standard Quarkus paths on the application port (8080) — the
hub does not re-root them. They carry **no authentication**: liveness/readiness are
harmless, but `/q/metrics` names your connectors in its labels. The TLS façade proxies
everything except `/realms/*` to the hub, so on a public origin `/q/*` is reachable
from the internet — block `/q/*` at the proxy or firewall if that bothers you (it
should).

`/api/platform/health` is the one that knows about lumnik: it walks the module
manifest registry and reports each module's version. It sits under `/api/platform/*`,
so prod requires a token with the `lm_integrator` role (dev-bypass waves it through
in `%dev`).

## Scraping with Prometheus

Point a scrape job at `/q/metrics`:

```yaml
scrape_configs:
  - job_name: lumnik-hub
    metrics_path: /q/metrics
    static_configs:
      - targets: ["your-hub-host:8080"]
```

That's the whole integration — no push gateway, no agent.

## Domain metrics

Beyond the standard JVM/HTTP/pool meters, the hub emits:

| Metric | Tags | When it moves |
|---|---|---|
| `lumnik_runs_total` | `status` (`Completed`, `Partial`, `Failed`, `Cancelled`) | a connector run reaches a terminal status |
| `lumnik_rate_limited_total` | `bucket` | a request was refused `429` by [rate limiting](rate-limits.md) — the tenant is in the WARN log line, deliberately not in a tag |
| `lumnik_dead_letters_total` | `reason` | a row is quarantined to the DLQ — real losses only; benign skips (duplicates, policy-excluded) do not count |
| `csv_filesource_list_total` | `transport`, `connector` | files listed on a remote source |
| `csv_filesource_list_duration_seconds` | `transport`, `connector` | time spent listing |
| `csv_filesource_open_total` | `transport`, `connector`, `result` (`ok`/`error`) | a file open attempt |
| `csv_filesource_bytes_read_total` | `transport`, `connector` | bytes downloaded from a remote file |
| `csv_filesource_after_process_total` | `transport`, `action`, `result` | a post-ingest file action (move/delete/…) |

Two caveats:

- **The run counters are best-effort, at-least-once.** They increment inside the
  run's transaction, so a rolled-back run may still have counted. The authoritative
  loss signal is the DB-derived `deadLetterCount` on the run itself, not the counter.
- **The `csv_filesource_*` family fires only on the one-shot ingest path**
  (`lm csv ingest` → `POST /api/platform/sources/csv/ingest`). Scheduled connector
  runs read files through a different path and do not emit them. If your CSV
  connector runs on a schedule, watch `lumnik_runs_total` and the DLQ, not
  `csv_filesource_*`.

Meters are registered lazily on first use — a fresh boot shows none of these until
the corresponding thing has happened once. Absence means "hasn't happened", not
"broken".

## Scheduled background work

The hub breathes on **eight clocks** — every one a Quarkus `@Scheduled` tick with
`concurrentExecution = SKIP` (a tick that finds the previous one still running skips
its turn; the guarantee is per-JVM, i.e. per hub instance):

| Clock | Cadence | What it does |
|---|---|---|
| Connector scheduler | every 1 min | fires each connector whose `spec.schedule` cron is due — the ingestion breath itself |
| Workflow dormancy sweep | every 15 min¹ | watches every declared lifecycle for a document sitting unmoved past its threshold |
| Webhook delivery | every 5 s | sweeps the event outbox and delivers each event to the tenant's registered integrations (`lm integration`) |
| Webhook retry | every 30 s | re-attempts failed deliveries with backoff — the failure journal (`lm outputs`) feeds from here |
| Email delivery | every 5 s | sweeps the outbox and mails events matching notification rules (`lm notify`) — best-effort: a failed send is logged and skipped; mocked until the SMTP knobs are set |
| Process reactions | every 5 s | evaluates `kind: Process` rules against fresh events |
| Audit dispatch | every 2 s | flushes audit events to their sinks |
| Event retention purge | daily 04:30 | trims the outbox — never past what the slowest consumer has seen |
| Audit retention purge | daily 04:45 | trims `core.audit_event` past the retention window (default 365 d; `0` disables) |

Three outbox consumers (webhook, email, process) share one sweeping mechanism with a
per-consumer cursor in `core.event_consumer`, so each advances independently and the
retention purge never trims past the slowest of them. Audit dispatch is a **separate
pipeline** (`core.audit_outbox` → `core.audit_event`, a `dispatched` flag, the admin
datasource) — it has no cursor there, and the purge neither sees nor waits for it.

¹ The dormancy sweep is the only cadence exposed as a config knob today — the
[workflow dormancy sweep](../apps/workflows.md#dormancy-the-memory-of-the-breathing)
watches every declared lifecycle for a document stuck past its threshold.

```properties
lumnik.workflow.sweep.every=15m
```

```bash
LUMNIK_WORKFLOW_SWEEP_EVERY=5m
```

A shorter interval means an overdue document is *noticed* sooner — it does not change the
threshold itself (`spec.dormancy.after`, declared per workflow). Restart the hub after
changing either form; like the rate-limit knobs, env vars only reach the process at boot.

## Traces to a collector

OpenTelemetry is compiled in but effectively off in the shipped stacks:

- `%dev` and `%test` disable the SDK outright.
- The self-host compose sets `QUARKUS_OTEL_SDK_DISABLED: "true"` because the stack
  ships no collector — without it, every boot spams
  `Connection refused: localhost:4317` retries.

To wire tracing, run a collector (OTLP/gRPC), then on the hub container:

```bash
QUARKUS_OTEL_SDK_DISABLED=false
OTEL_EXPORTER_ENDPOINT=http://your-collector:4317
```

and restart the hub (for selfhost: edit the `hub` environment in
`docker-compose.selfhost.yml`, then `deploy/selfhost/up.sh`). The endpoint default is
`http://localhost:4317`; only the traces exporter is configured — OTel metrics/logs
export is not set up.

## Logs

Console logs carry the tenant in every line (`%X{tenantId}` in the format); `%prod`
switches to JSON console logging, ready for whatever log shipper you already run.

## What does NOT exist yet

- **No dashboards or alert rules ship with lumnik** — the metrics above are a thin
  floor, bring your own Grafana.
- **No per-connector tag on `lumnik_runs_total`** — it counts runs by status only.
- **`Partial` is the status worth alerting on**, more than `Failed`: a failed run is loud and
  retried, while a partial one wrote everything it read and simply did not read everything there
  was. It means the reader stopped on its own budget, or the run began past records the source had
  already deleted under its stored position. The run's `error_message` names which.
  For per-connector history, query the run ledger (`lm runs`, or the
  `connector.connector_run` table).
- **No row-count / duration metrics per run** — those live on the run record in the
  DB, not in Prometheus.
- **No RAG/chat metrics** — token usage, ask-to-SQL guard verdicts and abstentions
  are not metered today.
- **No OTel metrics or logs export** — traces only.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `lumnik_runs_total` / `lumnik_dead_letters_total` absent from `/q/metrics` on a fresh boot | Meters register lazily on first use | Absence means "hasn't happened yet on this process", not broken — trigger a run, or check `deadLetterCount` on the run record directly, which is authoritative regardless of the counter. |
| A scheduled CSV connector shows nothing under `csv_filesource_*` even though it's clearly ingesting | That metric family only fires on the one-shot ingest path (`lm csv ingest` / `POST /api/platform/sources/csv/ingest`) — scheduled runs go through a different code path and don't emit it | Watch `lumnik_runs_total` and the DLQ for a scheduled CSV connector instead. |
| No traces reach the collector, even with one running | `%dev`/`%test` disable the OTel SDK outright, and the self-host compose sets `QUARKUS_OTEL_SDK_DISABLED: "true"` by default (no collector ships with the stack) | Set `QUARKUS_OTEL_SDK_DISABLED=false` and `OTEL_EXPORTER_ENDPOINT` on the hub container, then restart it. |
| `GET /api/platform/health` returns `401`/`403` | It sits under `/api/platform/*`, which requires a token with `lm_integrator` (dev-bypass only waves it through in `%dev`) | Use a token carrying `lm_integrator`, or query the unauthenticated `/q/health` instead if you only need up/down. |
| Changed `lumnik.workflow.sweep.every` (or its env form) but the sweep still runs on the old cadence | Env vars, like the rate-limit knobs, are only read at process boot | Restart the hub after changing either form. |
