# Observability

> Health probes and Prometheus metrics, live on every boot; OpenTelemetry tracing, off until
> you point it at a collector. No dashboards, alert rules or RAG/chat metrics ship with them.

## What's exposed out of the box

| Endpoint | What it answers | Auth |
|---|---|---|
| `GET /q/health` | overall UP/DOWN, all checks (the database connections) | none |
| `GET /q/health/live` | "the process is alive" — watched by the selfhost compose healthcheck | none |
| `GET /q/health/ready` | "I can serve traffic" — the Helm chart's startup and readiness probe | none |
| `GET /q/metrics` | Prometheus text format: JVM, HTTP server, and the [domain metrics](#domain-metrics) below | none |
| `lm module list` | each installed module, its version and whether it is active | `lm_integrator` |

```
$ lm module list
NAME      VERSION  SCHEMA              STATUS    REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
llm       0.1.0    Model provider      INACTIVE  disabled:lumnik.ai.enabled
rag       0.1.0    Platform RAG        INACTIVE  disabled:lumnik.ai.enabled
```

The `/q/*` endpoints sit on the application port (8080):

- **They carry no authentication**, and `/q/metrics` names your connectors in its labels.
- **The TLS façade forwards them**: it sends everything except `/realms/*` and `/resources/*` to
  the hub. On a public origin, block `/q/*` at your proxy or firewall.

`GET /api/platform/health` returns the same module data plus the Search provider's state, for a
monitoring probe. It needs an `lm_integrator` token, except under the Helm chart's default
profile (`k8sdev`), where all of `/api/platform/*` answers without one.

## Scraping with Prometheus

Point a scrape job at `/q/metrics`:

```yaml
scrape_configs:
  - job_name: lumnik-hub
    metrics_path: /q/metrics
    static_configs:
      - targets: ["your-hub-host:8080"]
```

No push gateway, no agent.

## Domain metrics

Besides the JVM and HTTP meters, the hub emits:

| Metric | Tags | When it moves |
|---|---|---|
| `lumnik_runs_total` | `status` (`Completed`, `Partial`, `Failed`, `Cancelled`) | a connector run reaches a terminal status |
| `lumnik_rate_limited_total` | `bucket` | a request is refused `429` by [rate limiting](rate-limits.md); the tenant is in the WARN log line |
| `lumnik_dead_letters_total` | `reason` | a row is quarantined to the DLQ — real losses only; benign skips (duplicates, policy-excluded) do not count |
| `csv_filesource_list_total` | `transport`, `connector` | files listed on a remote source |
| `csv_filesource_list_duration_seconds` | `transport`, `connector` | time spent listing |
| `csv_filesource_open_total` | `transport`, `connector`, `result` (`ok`/`error`) | a file open attempt |
| `csv_filesource_bytes_read_total` | `transport`, `connector` | bytes downloaded from a remote file |
| `csv_filesource_after_process_total` | `transport`, `action`, `result` | a post-ingest file action (move/delete/…) |
| `lumnik_search_projection_lag_seconds` | — | age of the oldest change not yet in a search index |
| `lumnik_search_projection_lagging_indexes` | — | how many search indexes have a pending change |
| `lumnik_search_rebuild_total` | `outcome` | a search index rebuild ends |
| `lumnik_search_rebuild_duration_seconds` | `outcome` | how long that rebuild took |
| `lumnik_search_catchup_failures_total` | — | a search index catch-up attempt failed |

```
$ curl -s http://localhost:8080/q/metrics | grep '^lumnik_'
lumnik_runs_total{status="Failed"} 1.0
lumnik_runs_total{status="Completed"} 5.0
lumnik_dead_letters_total{reason="MalformedSource"} 2.0
```

- **A meter appears once its event has happened** on this process: absence means "not yet", not
  "broken". The `lumnik_search_*` meters appear only on a hub built with a
  [Search provider](../search/search-indexes.md#choosing-a-provider).
- **The run counters may over-count slightly**; the run record (`lm run get`) is authoritative.
- **`csv_filesource_*` moves only for `lm csv ingest`.** Scheduled CSV connector runs do not emit
  it: watch `lumnik_runs_total` and the DLQ for them.

## What to alert on

- **`Partial` runs**, more than `Failed`: a failed run is loud and retried, a partial one wrote
  what it read but did not read everything. Its `error_message` (`lm run get <id>`) says why.
- **Per-connector history** is in the run ledger, not in Prometheus:

```
$ lm run list --connector orders
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
9c64afbf  orders     default   Completed  9   9    0        0             2026-10-19 01:40  2026-10-19 01:40
8778809f  orders     default   Completed  10  10   0        0             2026-10-19 01:39  2026-10-19 01:39
6e52921c  orders     default   Completed  10  8    2        2             2026-10-19 01:38  2026-10-19 01:38
```

## Scheduled background work

Each hub process runs these jobs; a job still busy when its next turn comes skips that turn.

| Job | Cadence | What it does |
|---|---|---|
| Connector scheduler | every 1 min | starts each connector whose `spec.schedule` is due; marks runs left `Running` by a dead process as `Failed` |
| Export scheduler | every 1 min | starts each export whose schedule is due |
| Connector run queue | every 5 s² | starts queued manual connector runs ([run queue](upgrade.md#turning-on-the-run-queue)) |
| Export run queue | every 5 s² | starts queued on-demand export runs |
| Workflow dormancy sweep | every 15 min¹ | flags documents sitting unmoved past their [dormancy threshold](../apps/workflows.md#dormancy-the-memory-of-the-breathing) |
| Webhook delivery | every 5 s | delivers new events to the tenant's integrations (`lm integration`) |
| Webhook retry | every 30 s | re-attempts failed deliveries with backoff — the failure journal (`lm outputs`) |
| Email delivery | every 5 s | mails events matching notification rules (`lm notify`); a failed send is logged and skipped; mocked until the SMTP settings are set |
| Process reactions | every 5 s | evaluates `kind: Process` rules against new events |
| Audit dispatch | every 2 s | writes pending audit events to the audit trail |
| Search projection | every 1 min | brings search indexes up to date (Search provider builds only) |
| Search reconciler | every 1 min | finds changed rows no event reported to Search (Search provider builds only) |
| Event retention purge | daily 04:30 | trims the event outbox — never past what the slowest consumer has seen |
| Audit retention purge | daily 04:45 | trims the audit trail past its window (default 365 d; `0` disables) |
| Webhook journal purge | daily 05:00 | trims the webhook delivery journal (default 90 d) |

The retention windows are in [sizing](sizing.md#storage-the-sizing-question-this-page-does-not-answer).

¹ `lumnik.workflow.sweep.every` (env `LUMNIK_WORKFLOW_SWEEP_EVERY`). A shorter interval notices an
overdue document sooner; the threshold itself is `spec.dormancy.after`, per workflow.

² `LUMNIK_RUN_QUEUE_POLL_INTERVAL`, passed through by both the selfhost compose file and the Helm
chart (`runQueue.pollInterval`).

To change it: selfhost, set it in the root `.env` and recreate the hub; Helm, pass it through
`hub.extraEnv`. Both gestures are in [rate limits](rate-limits.md#tuning).

## Traces to a collector

OpenTelemetry is off in the shipped stacks:

- The `dev` profile disables it.
- The selfhost compose file defaults `QUARKUS_OTEL_SDK_DISABLED` to `true` (no collector ships).
- The Helm chart sets `QUARKUS_OTEL_SDK_DISABLED: "true"` in the hub ConfigMap, for the same reason.
  Check what your release renders:

  ```console
  $ helm template lumnik deploy/helm/lumnik -f my-values.yaml -s templates/hub-configmap.yaml | grep OTEL
    QUARKUS_OTEL_SDK_DISABLED: "true"
  ```
- Only traces are exported — no OTel metrics or logs.

To send traces, run a collector (OTLP/gRPC) and set on the hub container:

```bash
QUARKUS_OTEL_SDK_DISABLED=false
OTEL_EXPORTER_ENDPOINT=http://your-collector:4317   # default: http://localhost:4317
```

Selfhost: set both in the root `.env` and recreate the hub. Helm: pass both through
`hub.extraEnv` — an entry there wins over the ConfigMap's default. Both gestures are in [rate limits](rate-limits.md#tuning).

## Logs

Every console line carries the tenant id (blank outside a tenant's request). The `prod` profile writes JSON lines, ready for your log
shipper. Recipes: [logs](../recipes/logs.md).

## What does not exist { #what-does-not-exist }

- **No dashboards or alert rules** — bring your own Grafana.
- **No per-connector tag on `lumnik_runs_total`** — use `lm run list --connector`.
- **No row-count or duration metric per run** — those are on the run record (`lm run get`).
- **No RAG/chat metrics** — token usage, ask-to-SQL verdicts and abstentions are not metered.
- **No OTel metrics or logs export** — traces only.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `lumnik_runs_total` / `lumnik_dead_letters_total` absent from `/q/metrics` on a fresh boot | nothing has happened yet on this process | Trigger a run, or read the run record (`lm run get`). |
| A scheduled CSV connector shows nothing under `csv_filesource_*` | those meters move only for `lm csv ingest` | Watch `lumnik_runs_total` and the DLQ instead. |
| No traces reach the collector | OTel is disabled in the shipped stacks | See [Traces to a collector](#traces-to-a-collector). |
| `GET /api/platform/health` returns `401`/`403` | it needs an `lm_integrator` token | Use an `lm_integrator` account (then `lm module list`), or `/q/health` for up/down only. |
| Set `LUMNIK_WORKFLOW_SWEEP_EVERY` in `.env`, cadence unchanged | the hub reads it at startup only | Recreate the hub; see [Scheduled background work](#scheduled-background-work). |
