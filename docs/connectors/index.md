# Connectors

> Connectors ingest your legacy sources — files, databases, APIs, event topics — into
> hub tables, read-only and declaratively: one YAML manifest, three commands —
> validate, apply, run.

## Which connector for which source?

| Your source | Connector | Page |
|---|---|---|
| CSV exports — dropped locally, on SFTP, or in S3 | `csv-file` | [CSV & files](csv.md) |
| A live database (PostgreSQL — the only dialect in v1) | `jdbc-generic` | [JDBC](jdbc.md) |
| An HTTP/JSON API (Stripe, HubSpot, …) | `rest-generic` | [REST](rest.md) |
| A GraphQL API | `rest-generic`, GraphQL shape | [GraphQL](graphql.md) |
| A SOAP middleware (Siebel, SAP PI, …) | `rest-generic`, SOAP shape | [SOAP](soap.md) |
| An event broker (Kafka, Redpanda, Event Hubs) | `kafka` — **Pro** | [Kafka](kafka.md) |

There are **five connector types** (`csv-file`, `jdbc-generic`, `rest-generic`, `kafka`,
`hello-world`). GraphQL and SOAP are not types — they are per-endpoint *request shapes*
on the REST engine, sharing its auth, retry, rate-limiting and storage. (`hello-world` is
the smoke-test connector — it has no manifest page of its own.)

## The pipeline

```mermaid
flowchart LR
  src["legacy source"] --> t["transport / engine"] --> p["parser"]
  p --> m["mapping & transforms"] --> tbl["hub table"] --> rag["RAG chunks"]
```

Every connector follows the same path: read from the source, parse, optionally
transform ([Transformers](transformers.md)), write to a tenant-isolated hub table,
index into the RAG corpus for [scoped chat](../rag/scopes.md).

## The lifecycle — every connector, same three commands

Assumes a deployed hub and an authenticated `lm` — see [Quickstart](../quickstart/installer.md)
if you haven't set those up yet.

1. Write a manifest (`kind: Connector`) — every connector uses the same envelope:

    ```yaml
    apiVersion: connectors.lumnik.io/v1
    kind: Connector
    metadata:
      name: <kebab-case-name>
      connector_type: <csv-file|jdbc-generic|rest-generic|kafka>
      scopes: [<métier>]          # the scope(s) this connector's tables are askable in
    spec:
      # type-specific — see your connector's page
      ...
    ```

2. `lm validate -f manifest.yaml` — check it without touching anything.
3. `lm apply -f manifest.yaml` — register or update the connector (idempotent —
   safe to re-run).
4. `lm connector run <name>` — run it now; or declare `spec.schedule: "0 6 * * *"`
   (add `spec.window: "22:00-07:00"` to fence scheduled runs into a UTC time window —
   the end is exclusive, so `07:00` keeps that `06:00` slot inside)
   (cron, evaluated in UTC — this example fires daily at 06:00 UTC).

    What the three commands print, on a small orders export:

    ```
    $ lm validate -f orders.yaml
    ✓ orders.yaml — valid
    $ lm apply -f orders.yaml
    {"name":"orders","connectorId":"487ed9d1-58d5-4120-969c-dc448910e1f2","endpointsApplied":1}
    $ lm connector run orders
    run scheduled: f0466804-0824-4eb4-8aa2-54e263dccafe
    ```

    "Scheduled" is not "done" — the ledger is where the result lands, a few seconds later:

    ```
    $ lm run list --connector orders
    ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
    f0466804  orders     default   Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
    ```

    Ten rows read, eight written, two set aside with their reason (`lm dlq list`).
5. Watch it in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:sources` (connectors),
   `:runs` (history), `:dlq` (rejected rows — set aside instead of failing the run,
   inspected with `lm dlq list`).

**Scheduled runs take turns**: a scheduled connector runs each of its endpoints as its own run,
and a hub instance drives at most 4 of them at once (`lumnik.connector.max-concurrent-scheduled-runs`,
environment variable `LUMNIK_CONNECTOR_MAX_CONCURRENT_SCHEDULED_RUNS`). The others wait and start as
those finish; an endpoint still waiting has no run in `lm run list` yet. Each run uses up to two connections of
the hub's `admin` pool (20 by default): raise the pool
(`quarkus.datasource."admin".jdbc.max-size`) along with the bound. With the run queue off (the default),
`lm connector run` does not count against it and never waits for it — so a manual run of a connector with
many endpoints can still fill the pool on its own. A scheduled endpoint that waited does not start if,
meanwhile, the connector was paused or deleted, its window closed, or the endpoint already ran (a manual run).

**Manual runs can queue**: with `LUMNIK_RUN_QUEUE_ENABLED=true` (off by default; read
[Turning on the run queue](../operations/upgrade.md#turning-on-the-run-queue) first), `lm connector run`
and `POST /api/platform/connectors/{id}/run` answer 202 with one run per endpoint in status `Queued`, and the
hub starts each as a slot frees — on the same `LUMNIK_CONNECTOR_MAX_CONCURRENT_SCHEDULED_RUNS` budget as scheduled
runs, so a manual run of a 25-endpoint connector no longer fills the connection pool. `lm run list` shows the
`Queued` runs (no start time yet) and `lm run cancel` ends one. An endpoint holds at most one queued-or-running
run: a second request for it is answered with a run already `Cancelled` ("another run is already active").
A run not started within `LUMNIK_RUN_QUEUE_MAX_AGE` (default `1h`) ends `Cancelled`, "expired in the queue";
one whose endpoint was disabled or deleted, whose connector was paused or deleted, or whose definition was
re-applied while it waited, ends `Cancelled` with that reason. A run left `Running` by a hub that died
still blocks its endpoint until the hub's reaper fails it (within a minute), so a request in that window is
answered with a run already `Cancelled` rather than started. `lm run cancel` on a run the hub is just then
starting is refused with "is being started; try again in a moment". On-demand exports queue the same way ([Exports](../apps/exports.md#runs-can-queue)).

**Freshness follows the schedule**: a change in the source appears at the next run *that
reads it* — deletes never propagate, and watermark mode only re-reads rows whose
watermark advanced. How the *user* perceives that age is an open question the
[PWA page answers](../apps/pwa.md#how-old-is-what-im-looking-at).

## Validate before apply

| Command | Purpose |
|---|---|
| `lm validate -f manifest.yaml` | Validate without applying. Exit 0 if valid (warnings allowed), 1 if errors. |
| `lm apply -f manifest.yaml` | Apply — the same checks gate the server: on errors nothing is written and you get the same field-by-field report. Warnings do not block the apply; they are printed **on stderr** after it, so stdout stays the JSON body. |
| `lm describe connector-type <kind>` | Show the schema (fields, types, required/optional, docs) for a connector type — run it any time, before writing a single line of YAML. |

### Valid

```
$ lm validate -f stripe.yaml
✓ stripe.yaml — valid
```

### Errors only

```
$ lm validate -f bad.yaml
✗ bad.yaml — 2 error(s), 0 warning(s)

  ERROR    spec.auth.token_env
           required field missing (required when kind = bearer)

  ERROR    spec.endpoints[0].id
           must match kebab-case identifier

Fix errors above before running 'lm apply -f bad.yaml'.
```

### Warnings only (still valid, exit 0)

```
$ lm validate -f noscopes.yaml
! noscopes.yaml — 0 error(s), 1 warning(s)

  WARN     metadata.scopes
           no scopes declared — the connector's tables will not be askable in any métier
           → Add: scopes: [compta]  (the métier a user must hold as scope:compta)
```

`lm apply` prints the same report, on **stderr**, after the JSON body it writes to stdout —
so the advice reaches the integrator who skips `lm validate`, without a pipe reading the body
ever seeing it:

```
$ lm apply -f noscopes.yaml
{"name":"noscopes","connectorId":"487ed9d1-58d5-4120-969c-dc448910e1f2","endpointsApplied":1}
! noscopes.yaml — 0 error(s), 1 warning(s)

  WARN     metadata.scopes
           no scopes declared — the connector's tables will not be askable in any métier
           → Add: scopes: [compta]  (the métier a user must hold as scope:compta)
```

The exit code is 0 either way: a warning has never failed a validate, and a CI gate built on
that must not start failing here.

### Exit codes

| Exit | Meaning |
|---|---|
| 0 | No errors (warnings allowed) |
| 1 | One or more validation errors |
| 2 | Reserved: parse failure, file not found, server unreachable |
