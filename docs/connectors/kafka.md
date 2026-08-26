# Kafka

> **lumnik Pro.** Consume a Kafka-compatible topic (Kafka, Redpanda, Azure Event Hubs) into a hub table: each run catches up to the head and completes; the hub owns the offsets.

| | |
|---|---|
| **Type** | `kafka` |
| **Protocol** | Kafka wire (JSON messages) |
| **Target** | `connector.t_<name>` |
| **Schedulable** | cron |
| **Edition** | Pro |

Not available in the open edition — the connector type is absent there and a `kafka` manifest is refused at validate.

## Quickstart

```yaml
--8<-- "docs/connectors/kafka/10-kafka-json.yaml"
```

```bash
lm validate -f docs/connectors/kafka/10-kafka-json.yaml
lm apply -f docs/connectors/kafka/10-kafka-json.yaml
lm connector run kafka-erp-exports
```

## How it behaves

- **A run catches up, chunk by chunk.** The engine loops chunks within one run — each chunk is
  one consumer poll batch, written in its own transaction — until an empty poll says the topic
  is caught up, which completes the run. Each message's top-level JSON fields become table
  columns in `connector.t_<name>` (schema inferred automatically, the same auto-schema behavior
  as JDBC and CSV). Kafka manifests carry **no mapping hooks** — there is no `spec.mapping`
  block on this connector type; see [Transformers](transformers.md) for what a mapping block
  *would* do on a connector that supports it.
- **First run = full-retention ingest**: a partition (one ordered slice of the topic) with no
  stored cursor seeks to the beginning, so run #1 reads everything the topic still retains.
  Subsequent runs resume at the stored partition→offset (each partition's saved read position)
  cursor.
- **Non-JSON messages go to the per-message DLQ** (`MalformedSource`) and never block ingestion —
  the same discipline as the CSV per-row DLQ. Their **offsets still advance**: a poison message
  is dead-lettered once and never re-read. A poll of *only* poison keeps polling for real rows,
  bounded by the chunk budget, so even a continuously-poison topic cannot wedge a run — the
  advanced cursor is persisted and the next run resumes past the poison.
- **Dedup by content hash** (`_row_hash`): re-ingesting the same message is a silent conflict;
  `data.row.changed` fires only for genuinely-new rows.
- **Seven column names are reserved.** `id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`,
  `_mapping_version`, `tenant_id` — the hub adds these to every `connector.t_<name>` table. A
  top-level JSON field sharing one of them is refused at write time, on a brand-new table and on
  one that already exists alike. Because kafka manifests carry no mapping hooks, there is **no
  in-product rename here**: the field has to reach the hub under another name, which means
  changing what the producer puts on the topic. `id` is the one you will meet.

## Field reference

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | logical/table name → `connector.t_<name>` |
| `brokers` **or** `brokers_env` | one of the two | bootstrap servers, literal or via env var |
| `topic` | yes | must match `[a-zA-Z0-9._-]+` |
| `format` | no | v1 supports only `json` (Avro/Protobuf deferred) — one JSON object per message |
| `poll_timeout_ms` | no | consumer poll timeout, range **[100, 60000]**, default `2000` |
| `max_poll_records` | no | max records per consumer poll; positive integer, default `500`; clamped to the engine chunk size (500) |
| `auth` | no | `kind: none` (default) or `kind: sasl-plain` with `username_env` + `password_env` (+ optional `security_protocol`) |
| `target.tags` | no | tags for RAG scope filtering — see [Tags for scoped chat](#tags-for-scoped-chat) |

With `sasl-plain`, `auth.security_protocol` overrides the wire protocol — default `SASL_SSL`;
set `SASL_PLAINTEXT` for a SASL broker without TLS. The value lands verbatim in the consumer's
`security.protocol` (it is not validated at apply time).

**One connector reads one topic**, and this release has no message-key or header filtering —
whatever the topic carries lands whole in that connector's table. If a topic multiplexes
several event types together, give each event stream its own topic when you need them
separated into different tables.

### Tags for scoped chat

Declare `target: { tags: [...] }` in `spec` (or `tags:` at the top level under `metadata`) to
make ingested rows discoverable in [scoped chat](../rag/scopes.md) — the same mechanism CSV
and JDBC connectors use. One difference from those two: the kafka validator does not warn at
validate-time if tags are missing, so nothing will remind you to add them.

## Continuous consumption

A run is a **catch-up, not a subscription**: it drains the backlog to the head and completes.
To keep the table current, declare a `schedule:` cron in the manifest — like jdbc/csv/rest,
it lands on the scheduler (absent cron on re-apply = unschedule). Each run resumes at the
stored cursor and catches up again. Overlap is safe: a submission while a run is already
active on the endpoint is skipped (per-endpoint advisory lock), so an aggressive cadence
degrades to back-to-back catch-ups, never double ingestion. An external trigger works too:
cron + `lm connector run <name>`.

## Azure Event Hubs — the `event-hubs` preset

Vendor presets ride as `spec.preset` while `connector_type` stays `kafka`. The preset pre-wires
the bootstrap address (`<namespace>.servicebus.windows.net:9093`) and SASL/SSL from the
connection string. No Kafka consumer group is created or needed here either — the hub tracks
offsets itself, same as any other kafka connector, so there's nothing to provision or clean up
on the Event Hubs side.

**Prerequisite — two env vars where the hub runs.** Event Hubs' Kafka endpoint authenticates as
SASL/PLAIN with the **fixed username `$ConnectionString`** (the literal string, dollar sign
included — it is not a secret) and the namespace connection string as the password. The preset
reads the username from the fixed env var `EVENT_HUBS_SASL_USERNAME` and the password from the
env var you name in `connection_string_env`; both resolve from the **process environment** only
(OS env, then JVM system property) — the [`lm secret`](../admin-console-guide.md) store is not
consulted:

```bash
export EVENTHUBS_CONN_STR='Endpoint=sb://…'           # the namespace Primary Connection String
export EVENT_HUBS_SASL_USERNAME='$ConnectionString'   # the fixed literal username
```

```yaml
--8<-- "docs/connectors/kafka/20-event-hubs.yaml"
```

An unknown `preset` name is rejected at validate time (a typo never silently degrades to plain
kafka), and a preset missing its inputs (e.g. `namespace`) reports each missing field before
expansion.

## How to…

### Keep a topic mirrored on a cadence

```yaml
spec:
  schedule: "*/5 * * * *"   # every 5 minutes
```

**Result:** each scheduled run resumes at the stored partition→offset cursor and catches up
to the head again. A submission while a run is already active on the endpoint is skipped
(per-endpoint advisory lock) — an aggressive cadence degrades to back-to-back catch-ups, never
double ingestion. Watch it land in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:runs`
for the run's status.

### Connect Azure Event Hubs

```bash
export EVENTHUBS_CONN_STR='Endpoint=sb://…'           # the namespace Primary Connection String
export EVENT_HUBS_SASL_USERNAME='$ConnectionString'   # the fixed literal username
```

```yaml
--8<-- "docs/connectors/kafka/20-event-hubs.yaml"
```

**Result:** `lm connector run event-hubs-telemetry` connects over SASL/PLAIN using the two
exported variables and catches the Event Hub up into `connector.t_telemetry_events`. Watch it
land in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:runs`.

### Inspect messages that failed to ingest

```bash
lm dlq list
lm dlq get <id>
```

**Result:** every non-JSON message is dead-lettered under reason `MalformedSource`, one entry
per message, and its offset still advances so the run never stalls behind it. Watch the queue
in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:dlq`.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| first run ingests far more than expected | no stored cursor yet = a full-retention read, by design | let it complete — later runs are incremental |
| non-JSON messages missing from the target table | dead-lettered per message; offsets still advance | `lm dlq list` |
| auth failure against Event Hubs | username must be the literal `$ConnectionString` via `EVENT_HUBS_SASL_USERNAME`; password comes from `connection_string_env` — both resolve from the **process environment** only | export both where the hub runs |
| a run never seems to "subscribe" | a run is a catch-up, not a subscription | schedule a cron |
| run fails: `source column 'id' collides with a hub system column` | a top-level field shares one of the seven reserved names; kafka has no mapping hooks, so nothing can rename it inside lumnik | the producer must emit that field under another name — see [How it behaves](#how-it-behaves) |

## See also

- [Overview](index.md) — apply/validate flow shared by all connectors.
- [Transformers](transformers.md) — what runs, and doesn't, at ingestion (kafka runs
  carry none of these hooks).
- [Scopes](../rag/scopes.md) — how `target.tags` makes ingested rows discoverable in chat.
- Events *out* of the hub (outbox, `data.row.changed`) power the
  [Process rung](../apps/processes.md) of the ladder.
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
