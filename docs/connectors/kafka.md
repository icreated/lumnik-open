# Kafka

> **lumnik Pro.** Consume a Kafka-compatible topic — Apache Kafka, Redpanda, Azure Event Hubs —
> into a hub table. Each run reads from the stored position to the head the topic had when the
> run started, then completes; the hub owns the offsets, and nothing is ever written back to
> the broker.

| | |
|---|---|
| **Type** | `kafka` |
| **Protocol** | Kafka wire, JSON messages |
| **Target** | `connector.t_<name>` |
| **Offsets** | hub-owned, per partition — no consumer group on the broker |
| **Delivery** | at-least-once into a table deduplicated by content hash |
| **Schedulable** | cron |
| **Edition** | Pro — the type is absent from the open edition, where a `kafka` manifest is refused at validate |

## Quickstart

!!! note "Every screen on this page reads the demo broker that ships with the repo"

    ```bash
    docker compose -f docs/connectors/kafka/demo/docker-compose.yml up -d
    python3 docs/connectors/kafka/demo/acme-topics.py        # needs: pip install confluent-kafka
    ```

    The manifests point at `host.docker.internal:29092`: the hub runs in a container, where
    `localhost` is the hub itself. Topics and gestures: [The demo broker](kafka/demo-broker.md).

```yaml
--8<-- "docs/connectors/kafka/demo/10-quickstart.yaml"
```

```
$ lm validate -f docs/connectors/kafka/demo/10-quickstart.yaml
✓ 10-quickstart.yaml — valid
$ lm apply -f docs/connectors/kafka/demo/10-quickstart.yaml
{"name":"acme-kafka-partners","connectorId":"78b94d8a-72ca-46e4-9080-f025f3100298","endpointsApplied":1}
$ lm connector run acme-kafka-partners
run scheduled: c5fc42d2-87f9-4328-acd2-b29213562a54
```

"Scheduled" is not "done": the result lands in the run ledger a second or two later. Run it a
second time, then look:

```
$ lm connector run acme-kafka-partners
run scheduled: 39ae9f8f-65f8-405a-a6cf-42afb1b4e7e1
$ lm run list --connector acme-kafka-partners
ID        CONNECTOR            ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
39ae9f8f  acme-kafka-partners  default   Completed  0     0     0        0             2026-09-13 02:21  2026-09-13 02:21
c5fc42d2  acme-kafka-partners  default   Completed  2400  2400  0        0             2026-09-13 02:21  2026-09-13 02:21
```

The first run read the whole topic; the second found nothing new. `STARTED` / `ENDED` print in
your terminal's time zone (these screens: Paris, UTC+2); `schedule` is evaluated in UTC.

What landed in `connector.t_acme_partners` — each top-level JSON field became a column:

```
 partner_id |           name            |   city    | credit_limit |      updated_at      
------------+---------------------------+-----------+--------------+----------------------
       1000 | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01T00:00:00Z
       1001 | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01T00:01:00Z
       1002 | Fixations Milano 1002     | Milano    |          774 | 2026-01-01T00:02:00Z
       1003 | Pneumatique Porto 1003    | Porto     |          911 | 2026-01-01T00:03:00Z
(4 rows)
```

- **No schema to declare.** Column types come from the first chunk, in the message's field order:

    | JSON value | Column type |
    |---|---|
    | whole number | `bigint` |
    | number with a decimal point | `numeric(18,4)` |
    | string (timestamps included) | `text` |
    | boolean | `boolean` |
    | object, array | `jsonb` |

    A field added later: [Schema evolution](#schema-evolution).
- **Nothing to provision on the broker.** No consumer group; the position is stored by the hub —
  [Partitions and the cursor](#partitions-and-the-cursor).
- **A message read twice is one row** — [Storage layout](#storage-layout).

## The manifest

```yaml
spec:
  name: erp_events                  # required — the table: connector.t_erp_events
  brokers: broker-1:9092,broker-2:9092   # bootstrap servers, literal…
  # brokers_env: KAFKA_BROKERS      # …or by name, resolved at run time (lm secret, then the hub's environment)
  topic: erp.events                 # required — one topic per connector
  format: json                      # the only value in v1: one JSON object per message
  poll_timeout_ms: 2000             # optional, [100, 60000]; what one poll may wait for the broker
  max_poll_records: 500             # optional, ≥ 1; clamped to the 500-row chunk
  auth:                             # optional; omitted = no SASL handshake
    kind: sasl-plain                # none | sasl-plain
    username_env: KAFKA_USER        # names, never values
    password_env: KAFKA_PW
    security_protocol: SASL_SSL     # or SASL_PLAINTEXT for a SASL broker without TLS
  schedule: "*/5 * * * *"           # optional cron, evaluated in UTC by the hub scheduler
  window: "06:00-22:00"             # optional fence for the scheduled slots, UTC, end exclusive
```

There is no `target` and no `mapping` block: the table is `connector.t_<name>` and messages land
as they are. Three more keys belong to the [Event Hubs preset](#azure-event-hubs-the-event-hubs-preset):
`preset`, `namespace`, `connection_string_env`.

### Configuration reference

`lm describe connector-type kafka` prints every key with its type and meaning:

```
$ lm describe connector-type kafka
Connector type: kafka

name                 REQUIRED  [STRING, HIGH]
  Logical connector/table name (target table connector.t_<name>)
brokers              optional  [STRING, HIGH]
  Comma-separated bootstrap servers
brokers_env          optional  [STRING, HIGH]
  Env var holding the bootstrap servers
topic                REQUIRED  [STRING, HIGH]
  Kafka topic to consume
format               optional  [STRING, MEDIUM]
  Record value format (default json)
poll_timeout_ms      optional  [LONG, LOW]
  Consumer poll timeout in ms [100, 60000]
max_poll_records     optional  [LONG, LOW]
  Max records per poll (positive integer; clamped to the engine chunk size)
auth                 optional  [NESTED, MEDIUM]
  Broker authentication (kind: none | sasl-plain)
  kind                 optional  [STRING, HIGH]
    Authentication method (none | sasl-plain)
  username_env         optional  [STRING, HIGH]
    SASL username source: lm secret name, else env var
  password_env         optional  [STRING, HIGH]
    SASL password source: lm secret name, else env var
  security_protocol    optional  [STRING, MEDIUM]
    Wire protocol for sasl-plain (SASL_SSL | SASL_PLAINTEXT)
schedule             optional  [STRING, MEDIUM]
  Cron expression for recurring catch-up runs — the hub scheduler fires it, evaluated in UTC
window               optional  [STRING, MEDIUM]
  Fence scheduled runs to a UTC time window, HH:MM-HH:MM (a manual run bypasses it)
preset               optional  [STRING, LOW]
  Vendor source preset expanded at apply time (e.g. event-hubs)
namespace            optional  [STRING, LOW]
  Input of the event-hubs preset: the Azure Event Hubs namespace
connection_string_env optional  [STRING, LOW]
  Input of the event-hubs preset: env var holding the namespace connection string
```

| Key | Default | Rule |
|---|---|---|
| `name` | — | a plain identifier: letters, digits, underscore, not starting with a digit — it becomes `connector.t_<name>` |
| `brokers` / `brokers_env` | — | at least one is required; when both are set, `brokers` is used. `brokers_env` is a name the hub resolves when the run starts |
| `topic` | — | must match `[a-zA-Z0-9._-]+` |
| `format` | `json` | the only accepted value; Avro and Protobuf are [not yet supported](#limitations) |
| `poll_timeout_ms` | `2000` | integer in `[100, 60000]` |
| `max_poll_records` | `500` | integer ≥ 1; a value above the chunk size is clamped to it — see [Chunks](#chunks-and-the-two-poll-knobs) |
| `auth.kind` | `none` | `none` or `sasl-plain`; `sasl-plain` requires `username_env` and `password_env`, and refuses a blank one |
| `auth.security_protocol` | `SASL_SSL` | lands verbatim in the consumer's `security.protocol`; it is not validated at apply |
| `schedule` | — | five-field cron, validated at apply, evaluated in UTC |
| `window` | — | `HH:MM-HH:MM`, UTC, end exclusive, may cross midnight; a manual run ignores it |
| `metadata.scopes` | — | the métier scope(s) the table is askable in; the validator warns when none is declared |

`lm validate` and `lm apply` enforce every rule above. Five mistakes in one file, all named at once:

```yaml
--8<-- "docs/connectors/kafka/demo/90-refused.yaml"
```

```
$ lm validate -f docs/connectors/kafka/demo/90-refused.yaml
✗ 90-refused.yaml — 5 error(s), 1 warning(s)

  ERROR    spec.name
          must be a plain identifier (letters, digits, underscore; not starting with a digit) — it becomes the hub table connector.t_<name>

  ERROR    spec.topic
          topic must match [a-zA-Z0-9._-]+

  ERROR    spec.format
          format v1 supports only 'json'
          → Use: format: json

  ERROR    spec.max_poll_records
          max_poll_records must be a positive integer (1..2147483647)

  ERROR    spec.auth.kind
          auth.kind must be 'none' or 'sasl-plain'

  WARN     spec.poll_timeout
          unknown key — ignored by the runtime
          → Remove it, or check the spelling

Fix errors above before running 'lm apply -f docs/connectors/kafka/demo/90-refused.yaml'.
Error: validation failed: 5 error(s)
```

Read the warning: **the key list is closed**, inside `auth` too.

- A misspelled key (`poll_timeout`), or `target`/`mapping` copied from another manifest, is named
  and ignored; the manifest still applies.
- A misspelled `security_protocol` leaves the default `SASL_SSL` in place: a plaintext SASL
  broker then fails in the TLS handshake.

## How a run reads a topic

### Partitions and the cursor

A topic has one or more partitions, each with its own **offsets**; the hub stores one position
per partition. `acme.orders` is keyed by partner over six partitions:

```yaml
--8<-- "docs/connectors/kafka/demo/20-six-partitions.yaml"
```

```
$ lm run list --connector acme-kafka-orders
ID        CONNECTOR          ENDPOINT  STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
3463bf93  acme-kafka-orders  default   Completed  24000  24000  0        0             2026-09-13 02:21  2026-09-13 02:21
```

The stored position after that run, checked in the database:

```sql
SELECT c.cursor_value FROM connector.connector_cursor c
  JOIN connector.connector k ON k.id = c.connector_id
 WHERE k.name = 'acme-kafka-orders';
```

```
                                              cursor_value
--------------------------------------------------------------------------------------------------------
 {"type": "KafkaOffset", "offsets": {"0": 3880, "1": 3770, "2": 3930, "3": 4120, "4": 4190, "5": 4110}}
(1 row)
```

- One key per partition, each the **next offset to read**; they sum to 24 000.
- A partition with no stored offset (first run, or a partition added since) is read from its
  beginning: **the first run reads everything the topic still retains**.
- No consumer group, no offset commit on the broker: nothing to provision or clean up there, and
  each connector keeps its own position.

### The head is the target

When a run starts, it reads the **head** of every partition (the high watermark), reads up to
it, then completes. A message produced while the run reads belongs to the **next** run:

```yaml
--8<-- "docs/connectors/kafka/demo/75-live.yaml"
```

The feeder wrote 200 messages a second for 30 seconds; the run was started 10 seconds in:

```
$ python3 docs/connectors/kafka/demo/acme-topics.py feed --rate 200 --seconds 30 &
$ sleep 10; lm connector run acme-kafka-live
run scheduled: 644240c1-6741-407a-ae57-43aecdb19f1f
  ▪ fed 3986 messages at 200.0/s into acme.live
$ lm connector run acme-kafka-live
run scheduled: f5046f38-66ed-4598-8a18-2106c09d7072
$ lm run list --connector acme-kafka-live
ID        CONNECTOR        ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
f5046f38  acme-kafka-live  default   Completed  2673  2673  0        0             2026-09-13 02:23  2026-09-13 02:23
644240c1  acme-kafka-live  default   Completed  1313  1313  0        0             2026-09-13 02:23  2026-09-13 02:23
```

The first run read the 1 313 messages present at its start and completed while the feeder was
still writing; the second read the 2 673 that arrived since. Together 3 986, the feeder's count.

A run is reported **`Partial`** in two cases only (a run that simply ends below the head stays
`Completed` — see [An empty poll](#an-empty-poll-and-what-it-does-not-prove)):

- the broker had already deleted offsets the stored position pointed to
  ([A log that moved under the cursor](#a-log-that-moved-under-the-cursor));
- the topic fed only unparsable messages until the chunk budget ran out
  ([Poison messages](#poison-messages-the-per-message-dlq)).

`Partial` is the status to alert on — see [Observability](../operations/observability.md).

### Chunks and the two poll knobs

A run writes in **500-row chunks**, each committed with its cursor. Two knobs shape the polls
that fill a chunk:

| Knob | Default | Effect |
|---|---|---|
| `max_poll_records` | 500 | records per poll; a larger value is clamped to 500, a smaller one gives more chunks (`100` → 25 chunks for 2 400 messages) |
| `poll_timeout_ms` | 2000 | how long one poll waits; on a slow link, 100 ms can [read nothing and end the run](#an-empty-poll-and-what-it-does-not-prove) |

A run on a topic that has not moved completes at once with `IN 0`.

### Continuous consumption

To keep the table current, declare a `schedule`. Each slot is a fresh catch-up from the stored
cursor to the head of that moment:

```yaml
--8<-- "docs/connectors/kafka/demo/30-cadence.yaml"
```

```
$ lm apply -f docs/connectors/kafka/demo/30-cadence.yaml
{"name":"acme-kafka-cadence","connectorId":"a0f0d035-9e11-4443-b87f-f1fe86c2c274","endpointsApplied":1}
$ lm connector list
NAME                TYPE   TENANT  SCHED        WINDOW       STATUS  LAST RUN
acme-kafka-cadence  kafka  1       */5 * * * *  06:00-22:00
```

(Other connectors, such as `acme-kafka-partners` from the Quickstart, list too, with their last
run.)

- **Evaluated in UTC** by the hub's scheduler. A re-apply without `schedule` unschedules the
  connector.
- **No overlap.** A slot that fires while a run is active is skipped and recorded as `Cancelled`
  with the reason.
- **`window` fences the slots.** A slot due outside `"06:00-22:00"` is skipped, never queued; the
  end is exclusive and the range may cross midnight (`"22:00-06:00"`). It gates the *start* only.
  A manual `lm connector run` ignores it.
- An external trigger works too: your own cron plus `lm connector run <name>`.

**One connector reads one topic**, with no key or header filtering. To land the same messages
twice, apply a second connector on the same topic with a different `name`.

## Storage layout

Nothing to set up: the first run with a row to write creates the table.

```
                                       Table "connector.t_acme_partners"
      Column      |           Type           | Collation | Nullable |                        Default
------------------+--------------------------+-----------+----------+-------------------------------------------------------
 id               | bigint                   |           | not null | nextval('connector.t_acme_partners_id_seq'::regclass)
 _row_hash        | text                     |           | not null |
 _ingested_at     | timestamp with time zone |           | not null | now()
 _source          | text                     |           |          |
 _run_id          | uuid                     |           |          |
 _mapping_version | integer                  |           |          |
 tenant_id        | bigint                   |           | not null |
 partner_id       | bigint                   |           |          |
 name             | text                     |           |          |
 city             | text                     |           |          |
 country          | text                     |           |          |
 credit_limit     | bigint                   |           |          |
 updated_at       | text                     |           |          |
Indexes:
    "t_acme_partners_pkey" PRIMARY KEY, btree (id)
    "t_acme_partners_idx_tenant_id" btree (tenant_id)
    "t_acme_partners_tenant_id__row_hash_key" UNIQUE CONSTRAINT, btree (tenant_id, _row_hash)
Policies:
    POLICY "tenant_isolation"
      USING ((tenant_id = (NULLIF(current_setting('app.current_tenant'::text, true), ''::text))::bigint))
```

- **The first seven columns** are the hub's, on every `connector.t_*` table. The rest come from
  the messages, in their field order.
- **`_row_hash` is the dedup**, unique within a tenant: a message read twice (a producer retry, a
  cursor reset) is one row. `data.row.changed` events ([Processes](../apps/processes.md), webhooks,
  notification rules) fire for new rows only.
- **The row-level security policy** is what keeps one tenant's rows invisible to another.
- **`records_out` counts writes, including the ones the table refused.** Reset the cursor and run
  again:

    ```
    $ lm endpoint reset-cursor acme-kafka-partners/default
    $ lm connector run acme-kafka-partners
    $ lm run list --connector acme-kafka-partners
    ID        CONNECTOR            ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
    dc41ba74  acme-kafka-partners  default   Completed  2400  2400  0        0             2026-09-13 02:25  2026-09-13 02:25
    ```

    `OUT 2400`, and the table still holds 2 400 rows: check the row count, not `SKIPPED`.

!!! warning "Deleting a kafka connector deletes its data"
    - `lm connector delete` removes the connector's rows from `connector.t_<name>`, its cursor
      and its run history. The table goes with the last connector writing into it.
    - The topic is untouched; re-applying the manifest reads it again from the beginning.
    - It is the only way to drop the rows — `lm endpoint purge` refuses a kafka endpoint:

    ```
    $ lm endpoint purge acme-kafka-partners/default
    Error: API error 400: This endpoint writes to a connector table whose rows are not tracked per endpoint, so it cannot be purged on its own. Deleting the connector removes its data, along with its run history.
    ```

## Schema evolution

The producer changed its mind at message 1 201 and added two fields. `acme.partners-drift`:

```yaml
--8<-- "docs/connectors/kafka/demo/50-drift.yaml"
```

```
$ lm run list --connector acme-kafka-drift
ID        CONNECTOR         ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
18c2f825  acme-kafka-drift  default   Completed  2400  2400  0        0             2026-09-13 02:21  2026-09-13 02:21
```

```
 column_name  | data_type
--------------+-----------
 partner_id   | bigint
 name         | text
 city         | text
 country      | text
 credit_limit | bigint
 updated_at   | text
 vat_number   | text
 segment      | text

 rows | with_vat_number | with_segment
------+-----------------+--------------
 2400 |            1200 |         1200
```

- **A new field becomes a new column** during the run. Rows written before it hold `NULL`; a field
  that disappears leaves its column `NULL` for later rows.
- **The new column is typed from the chunk that introduces it**, like the first chunk (a field
  that is `null` in every message of that chunk lands `text`). New columns come after the existing
  ones.
- The hub log names each added column and its type, in an `INFO` line
  `… column(s) added mid-run, typed from this chunk: <column> <type>, …`.
- To re-type after the producer has settled: `lm endpoint reset-cursor`, then a re-read.
- The scope's schema card is refreshed, so the chat knows the new column.

### Names the producer chose stay — and stay askable

A kafka manifest has **no `mapping` block**: the field names on the topic are the column names.
`acme.partners-raw` carries typical ERP names:

```yaml
--8<-- "docs/connectors/kafka/demo/55-raw.yaml"
```

```
 column_name  | data_type
--------------+-----------
 partner_id   | bigint
 Partner Name | text
 credit.limit | bigint
 MONTANT_€    | numeric
 address      | jsonb
 tags         | jsonb

 partner_id |       Partner Name        | credit.limit | MONTANT_€ |                    address                     |             tags
------------+---------------------------+--------------+-----------+------------------------------------------------+-------------------------------
       1000 | Hydraulique Lyon 1000     |          500 |    0.0000 | {"city": "Lyon", "street": "0 rue de l'Usine"} | ["erp", "partner", "batch-0"]
       1001 | Roulements Bruxelles 1001 |          637 |    1.5000 | {"city": "Bruxelles", "street": "1 rue de l'Usine"} | ["erp", "partner", "batch-1"]
```

A space, a dot, a currency sign or a capital is kept; the hub and the chat quote the name in SQL:

```
$ lm ask --scope billing "How many rows are in connector.t_acme_partners_raw and what is the largest MONTANT_€?"
There are 500 rows in connector.t_acme_partners_raw. The largest MONTANT_€ is 748.5000.
  data as of: 2026-09-13T00:23:26.656851Z (last completed run)

…
  1 row returned

  SQL: SELECT COUNT(*), MAX("MONTANT_€") AS max_montant_eur FROM "connector"."t_acme_partners_raw"
```

`address` and `tags` landed as `jsonb`, values intact, but **object key order** is not kept
(`{"street", "city"}` comes back `{"city", "street"}`). A
[`kind:Workflow`](../apps/workflows.md#names-the-source-chose) can be declared over such a column
as it is; see also [Names the source chose](../transparency/ask-honesty.md#names-the-source-chose).

### Seven column names are reserved

`id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id` are the
hub's. A top-level JSON field with one of these names fails the run. `id` is the usual one:

```yaml
--8<-- "docs/connectors/kafka/demo/58-reserved.yaml"
```

```
$ lm run get 7ebc090f
ID        CONNECTOR            ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
7ebc090f  acme-kafka-reserved  default   Failed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
✖ run failed: source column 'id' collides with a hub system column (_ingested_at, _mapping_version, _row_hash, _run_id, _source, id, tenant_id); it must reach the hub under another name — jdbc: source.tables[].rename; csv: a map-columns row hook under spec.mapping; kafka: no rename exists, the field must be renamed in the topic payload
```

There is **no rename on kafka**: the producer must send the field under another name.

## When the topic misbehaves

What goes wrong is the **topic** (a bad message, a deletion, a missing name) or **time** (a log
start that moved, a poll that came back empty).

### Poison messages — the per-message DLQ

`acme.partners-poison` holds 1 000 messages of which 143 are not a JSON object — a truncated
object, a JSON array, a JSON scalar:

```yaml
--8<-- "docs/connectors/kafka/demo/40-poison.yaml"
```

```
$ lm run get bd4d3df9
ID        CONNECTOR          ENDPOINT  STATUS     IN    OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
bd4d3df9  acme-kafka-poison  default   Completed  1000  857  143      143           2026-09-13 02:21  2026-09-13 02:21
⚠ 143 rows quarantined — see: lm dlq list --run bd4d3df9-a25b-4844-9a46-d25ef97d5dab
$ lm dlq list --run bd4d3df9-a25b-4844-9a46-d25ef97d5dab
ID        CONNECTOR          RUN       REASON           ATTEMPTS  MESSAGE
4d3fb883  acme-kafka-poison  bd4d3df9  MalformedSource  1         acme.partners-poison:0:997:997 — message value is not a JS…
844d1f31  acme-kafka-poison  bd4d3df9  MalformedSource  1         acme.partners-poison:0:990:990 — invalid JSON: Unexpected …
c1a2e423  acme-kafka-poison  bd4d3df9  MalformedSource  1         acme.partners-poison:0:983:983 — message value is not a JS…
$ lm dlq get 844d1f31
ID:        844d1f31-5a15-401e-8f5b-4564bd89f89f
Connector: acme-kafka-poison (98bea7c4-7052-4186-838a-79110ffa58e2)
Run:       bd4d3df9-a25b-4844-9a46-d25ef97d5dab
Reason:    MalformedSource
Attempts:  1
Message:   acme.partners-poison:0:990:990 — invalid JSON: Unexpected end-of-input within/between Object entries
Payload:   {"raw":["{\"broken\": "],"file":"acme.partners-poison:0:990","line":990}
```

- `IN 1000 = OUT 857 + SKIPPED 143`, and the table holds 857 rows.
- The coordinate is `topic:partition:offset`; the raw value travels with it (up to 8 192
  characters).
- **Offsets advance**: a poison message is dead-lettered once and never re-read. A topic feeding
  only poison ends the run `Partial` when the chunk budget runs out; the next run resumes past it.
- Inspect entries with [`lm dlq`](../cli/lm-reference.md) — `list`, `get`, then `discard`, or
  `replayed` to record that you re-sent the message yourself (the hub never re-reads it); watch the
  queue in the TUI under `:dlq`.

### Tombstones — a deletion is not garbage

On a compacted topic, a record with a **null value** is how a producer says *"this key is
deleted"*; log compaction keeps it as the deletion marker. `acme.partners-compacted` holds 1 000
partners, then 300 tombstones:

```yaml
--8<-- "docs/connectors/kafka/demo/45-compacted.yaml"
```

```
$ lm run get e6d58e37
ID        CONNECTOR             ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
e6d58e37  acme-kafka-compacted  default   Completed  1300  1000  300      300           2026-09-13 02:21  2026-09-13 02:21
⚠ 300 rows quarantined — see: lm dlq list --run e6d58e37-3c4f-4f3f-aef7-906d79812a2a
$ lm dlq get 42d8ea82
ID:        42d8ea82-9207-4f38-b8dc-cf06706676f3
Connector: acme-kafka-compacted (4448919e-1cef-432b-844a-dd9d78f76571)
Run:       e6d58e37-3c4f-4f3f-aef7-906d79812a2a
Reason:    MalformedSource
Attempts:  1
Message:   acme.partners-compacted:0:1299:1299 — Kafka tombstone (key '1299'): the message value is null — the producer deleted that key, and append-only ingestion dead-letters the marker rather than applying it as a delete
Payload:   {"raw":[],"file":"acme.partners-compacted:0:1299","line":1299}
```

Ingestion is **append-only**: a tombstone becomes one DLQ entry naming the deleted **key**, counted
as a skip (`1300 = 1000 + 300`); the rows around it land. A deletion that must reach the table has
to travel **as data**, in a field the producer sets.

### A topic that does not exist

```yaml
--8<-- "docs/connectors/kafka/demo/60-absent.yaml"
```

```
$ lm run get c1008671
ID        CONNECTOR          ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
c1008671  acme-kafka-absent  default   Failed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
✖ run failed: kafka topic 'acme.absent' does not exist on the broker: it reports no partition for that name, so there is nothing to read. Check spec.topic for a typo, or create the topic on the broker. The hub never creates one (an ingestion run must not write to its source).
```

- The hub never creates a topic, even on a broker that allows automatic creation.
- On an endpoint that already has a stored position, the message says the topic was read before:
  it was deleted or renamed on the broker, or the manifest's topic was edited.

### A log that moved under the cursor

Retention, compaction or a trim moves a partition's log start forward, past a stored position.
Replay it on the demo broker:

```yaml
--8<-- "docs/connectors/kafka/demo/70-trimmed.yaml"
```

```
$ lm connector run acme-kafka-trimmed                                    # run 1
$ python3 docs/connectors/kafka/demo/acme-topics.py feed --topic acme.partners-trimmed --rate 500 --seconds 2
  ▪ fed 607 messages at 500.0/s into acme.partners-trimmed
$ python3 docs/connectors/kafka/demo/acme-topics.py trim 5500
  ✓ acme.partners-trimmed now starts at offset 5500 (high watermark 5607): offsets 0..5499 are gone
$ lm connector run acme-kafka-trimmed                                    # run 2
$ lm run list --connector acme-kafka-trimmed
ID        CONNECTOR           ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
49b90bd6  acme-kafka-trimmed  default   Partial    107   107   0        0             2026-09-13 02:22  2026-09-13 02:22
c30cf76f  acme-kafka-trimmed  default   Completed  5000  5000  0        0             2026-09-13 02:22  2026-09-13 02:22
$ lm run get 49b90bd6 > /dev/null
• run partial: acme.partners-trimmed[0]: the stored position is offset 5000 but the broker's log now starts at 5500 — offsets 5000..5499 are no longer on the broker (retention, compaction or a trim removed them) and can never be read; resuming at 5500
```

- Run 2 read the 107 messages left and ended **`Partial`**, naming the lost range (5000..5499).
  Those messages cannot be recovered.
- A **first** run (or one after `lm endpoint reset-cursor`) on a topic whose start moved completes
  normally: nothing was lost for this connector.
- When it repeats: raise the topic's retention, or run more often.

### An empty poll, and what it does not prove

A run ends when a poll returns nothing. On a slow link with a short `poll_timeout_ms`, a poll can
time out before the first fetch and end the run with less than the topic holds.

- The run stays **`Completed`**; only the hub log carries a `WARN` starting
  `<topic>: the poll returned no record within N ms and the position is below the high watermark`.
- When a green run ingested less than expected, search the hub log for the topic's name, and raise
  `poll_timeout_ms`.

An empty topic, with no such line:

```
$ lm run list --connector acme-kafka-empty
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2b7a9ee6  acme-kafka-empty  default   Completed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
```

### A broker that does not answer

An address that does not answer, or refuses the connection, fails the run after about **60 s** —
here `brokers: 10.255.255.1:9092`:

```
$ lm run get ffb91ec8
ID        CONNECTOR        ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
ffb91ec8  acme-kafka-down  default   Failed  0   0    0        0             2026-10-19 07:28  2026-10-19 07:29
✖ run failed: the kafka broker did not answer within the client's metadata timeout (10.255.255.1:9092, topic acme.partners) — check the broker is up and reachable from the hub
```

A broker that comes back is picked up by the next scheduled slot.

A host name the hub cannot resolve (`brokers: no-such-host:9092`) fails at once, and every slot
repeats it until the manifest is fixed:

```
$ lm run get 38327a21
ID        CONNECTOR          ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
38327a21  acme-kafka-nohost  default   Failed  0   0    0        0             2026-10-19 07:28  2026-10-19 07:28
✖ run failed: Failed to construct kafka consumer — No resolvable bootstrap urls given in bootstrap.servers
```

## Auth and secrets

| `auth.kind` | Config | Wire |
|---|---|---|
| `none` | nothing — the default when `auth:` is omitted | plaintext, no SASL handshake |
| `sasl-plain` | `username_env` + `password_env`, optional `security_protocol` | SASL/PLAIN over `SASL_SSL` (default) or `SASL_PLAINTEXT` |

`brokers_env`, `username_env` and `password_env` are **names**, resolved when the run starts: the
[`lm secret`](../cli/lm-reference.md#data-runs-answers) registry of your tenant first, then the
hub's environment (OS variable, then JVM system property).

```yaml
--8<-- "docs/connectors/kafka/demo/62-brokers-env.yaml"
```

Run before the name resolves, the refusal names both places it looked:

```
$ lm run get 3e808fcf
ID        CONNECTOR               ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
3e808fcf  acme-kafka-brokers-env  default   Failed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
✖ run failed: 'ACME_KAFKA_BROKERS' (brokers_env) resolves to nothing — no 'lm secret' entry for this tenant, and nothing of that name in the hub's own environment
$ lm secret set ACME_KAFKA_BROKERS          # prompts at the keyboard; what you type is not shown
Created secret "ACME_KAFKA_BROKERS"
$ lm connector run acme-kafka-brokers-env
$ lm run list --connector acme-kafka-brokers-env
ID        CONNECTOR               ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
d071091c  acme-kafka-brokers-env  default   Completed  2400  2400  0        0             2026-09-13 02:22  2026-09-13 02:22
3e808fcf  acme-kafka-brokers-env  default   Failed     0     0     0        0             2026-09-13 02:21  2026-09-13 02:21
```

`sasl-plain` refuses a credential name that resolves to nothing or blanks, before connecting:

```yaml
--8<-- "docs/connectors/kafka/demo/65-sasl.yaml"
```

```
$ lm run get 28d58a30
ID        CONNECTOR        ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
28d58a30  acme-kafka-sasl  default   Failed  0   0    0        0             2026-10-19 01:39  2026-10-19 01:39
✖ run failed: 'ACME_KAFKA_USER' (username_env) resolves to nothing — no 'lm secret' entry for this tenant, and nothing of that name in the hub's own environment
```

Once both names resolve, a listener that does not speak SASL/PLAIN (the demo broker) refuses:

```
$ lm run get 9e962b07 > /dev/null
✖ run failed: Unexpected handshake request with client mechanism PLAIN, enabled mechanisms are []
```

- `security_protocol` is not validated at apply. `SASL_SSL` is the default; a SASL listener
  without TLS needs `SASL_PLAINTEXT`.
- SASL/SCRAM, mTLS and OAuth are [not yet supported](#limitations).

## Azure Event Hubs — the `event-hubs` preset

Event Hubs speaks Kafka with SASL/PLAIN: the username is the literal `$ConnectionString`, the
password the namespace connection string. `preset: event-hubs` writes that config at apply;
`connector_type` stays `kafka`.

```yaml
--8<-- "docs/connectors/kafka/20-event-hubs.yaml"
```

```
$ lm apply -f docs/connectors/kafka/20-event-hubs.yaml
{"name":"event-hubs-telemetry","connectorId":"55016bf3-406b-43ee-86c8-92da12ca26f3","endpointsApplied":1}
$ lm connector get event-hubs-telemetry
id: 55016bf3-406b-43ee-86c8-92da12ca26f3
tenantId: 1
name: event-hubs-telemetry
typeId: kafka
enabled: true
config:
    auth:
        kind: sasl-plain
        password_env: EVENTHUBS_CONN_STR
        security_protocol: SASL_SSL
        username_env: EVENT_HUBS_SASL_USERNAME
    brokers: my-namespace.servicebus.windows.net:9093
    format: json
    kind: kafka
    name: telemetry_events
    topic: telemetry
tags:
    - demo
    - azure
```

The stored config is plain kafka: `preset`, `namespace` and `connection_string_env` are gone. Set
the two names it reads:

```bash
lm secret set EVENTHUBS_CONN_STR          # the namespace Primary Connection String, Endpoint=sb://…
lm secret set EVENT_HUBS_SASL_USERNAME    # the fixed literal $ConnectionString
```

`lm validate` refuses an unknown preset (`unknown source preset 'event-hubbs'`) and names each
missing input (`preset 'event-hubs' requires 'namespace'`).

## Limitations

- **JSON only.** One JSON object per message value. Avro, Protobuf and a Schema Registry are not
  yet supported; `format` accepts only `json`.
- **One topic per connector**, and no filtering by message key or header: whatever the topic
  carries lands in the table. Message keys, headers and timestamps are not stored as columns.
- **No mapping block.** No hooks run at ingestion — no rename, no flatten, no filter, no lookup.
  A field must reach the hub under the name and shape it should land with.
- **No pre-flight.** There is no `lm source kafka test`; the first run is the test. A missing
  topic or an unresolved secret fails it at once.
- **Append-only.** A tombstone is set aside, never applied as a delete; a deletion that must reach
  the table has to travel as data.
- **`sasl-plain` only.** No SASL/SCRAM, no mTLS, no OAuth.
- **Object key order** inside a nested value does not survive `jsonb`.

## CLI

```bash
lm validate -f <file.yaml>
lm apply -f <file.yaml>
lm describe connector-type kafka                    # the configuration reference, from the validator itself
lm connector run <connector>
lm connector get <connector>                        # the stored config, presets expanded
lm endpoint reset-cursor <connector>/default        # forget the position: the next run reads from the beginning
lm run list --connector <connector>
lm dlq list --run <run-id> · lm dlq get <id>
```

## TUI

- `:sources` — the `KIND` column reads `kafka`; Enter drills into the single `default` endpoint
- `:runs` — one row per run, `Partial` in its own colour beside `Completed` and `Failed`
- `:dlq` — the quarantined messages, with coordinates

Full reference: [CLI & TUI](../cli/lm-reference.md).

## How to…

### Keep a topic mirrored on a cadence

```yaml
spec:
  schedule: "*/5 * * * *"
  window: "06:00-22:00"      # optional
```

**Result:** every five minutes, a catch-up to the head; night slots and slots that meet a running
run are skipped. See [Continuous consumption](#continuous-consumption).

### Read a topic again from the beginning

```bash
lm endpoint reset-cursor <connector>/default
lm connector run <connector>
```

**Result:** `reset-cursor` prints nothing and exits 0; the run reads everything the topic retains.
Rows already in the table are not duplicated but are counted in `OUT`.

### Inspect the messages that did not land

```bash
lm run get <run-id>                 # the ⚠ line says how many, and the exact command to list them
lm dlq list --run <run-id>
lm dlq get <id>                     # coordinates, reason, raw value
```

**Result:** one entry per message, reason `MalformedSource`, coordinate `topic:partition:offset`.
A tombstone's entry names the deleted key; a poison message's entry carries the raw value.

### Read the same topic into two tables

Apply a second manifest with the same `topic` and a different `name`. Each connector has its own
cursor and its own table; the broker sees two independent readers.

**Result:** two connectors over one topic, useful when two métiers need the same events under
two scopes.

### Move to Event Hubs

Replace `brokers` and `auth` with `preset: event-hubs`, `namespace` and `connection_string_env`,
and set the two secrets. **Result:** the same run ledger, the same table; see
[the preset](#azure-event-hubs-the-event-hubs-preset).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| first run ingests far more than expected | no stored position yet — a full-retention read, by design | let it complete; later runs are incremental |
| run completes with `IN 0` on a topic that has data, and the hub log says `the poll returned no record within N ms` | the first poll timed out before its first fetch — or those offsets hold no readable record | raise `poll_timeout_ms`; if it repeats on a compacted or transactional topic that is genuinely caught up, it is the watermark, not the run |
| a run ends `Partial` | it stopped below the head it found at start: the broker deleted offsets the stored position was entitled to, or the topic fed only unparsable messages up to the chunk budget | `lm run get` names which; for the first, nothing can be recovered — check `retention.ms` against your cadence |
| run fails: `kafka topic '…' does not exist on the broker` | no partition reported under that name, and the hub never creates one | first read: check `spec.topic` or create the topic; stored position: the topic was deleted or renamed on the broker |
| run fails: `'NAME' (brokers_env) resolves to nothing` | neither the registry nor the hub's environment holds it | `lm secret set NAME`, or export it where the hub runs |
| run fails: `Unexpected handshake request with client mechanism PLAIN` | the listener does not speak SASL/PLAIN — wrong port, or `auth` on a plaintext broker | check the broker's listener; drop `auth` for a plaintext one |
| run fails after ~60 s: `the kafka broker did not answer within the client's metadata timeout` | the bootstrap address answers nothing — wrong host, wrong listener, or a broker that is down | from the hub's container, prove the address; on Docker, `host.docker.internal`, not `localhost` |
| run fails at once: `No resolvable bootstrap urls given in bootstrap.servers` | the hub cannot resolve the broker host name (e.g. `host.docker.internal` on a hub not running in Docker) | use a host name the hub resolves |
| run fails: `connector.t_… is already written by connector '…'` | another connector of the tenant already writes `connector.t_<name>` | give this endpoint another `name` — see [JDBC](jdbc.md#two-connectors-reading-a-table-of-the-same-name) |
| run fails: `source column 'id' collides with a hub system column` | a top-level field shares one of the seven reserved names | the producer must emit it under another name — no rename exists on kafka |
| messages missing from the table, `DEAD-LETTERS` > 0 | not a JSON object — dead-lettered per message, offsets advance | `lm dlq list --run <id>`; fix the producer and re-send, then `lm dlq replayed` or `discard` |
| rows missing, `lm dlq get` says `a Kafka tombstone (key '…')` | the producer deleted that key; ingestion is append-only | nothing to repair — if deletions must reach the table, send them as data |
| a scheduled slot shows `Cancelled: skipped: another run is already active` | the previous catch-up was still running | nothing — the next slot catches up; widen the cadence if it repeats |
| a column is `NULL` for the first N rows and filled after | the producer added the field mid-topic — [schema evolution](#schema-evolution) | expected; reset the cursor to re-read if the early rows carry it on the broker now |
| `lm validate` warns `unknown key` on `target` or `mapping` | copied from a jdbc or csv manifest; kafka has neither | remove the block |
| `lm validate` refuses the manifest: the connector type `kafka` is unknown to the hub | the hub is the open edition | Kafka is a Pro connector |

## See also

- [The demo broker](kafka/demo-broker.md) — the source every screen here reads
- [Overview](index.md) — apply/validate flow shared by all connectors
- [Transformers](transformers.md) — the hooks other connectors run at ingestion, and that kafka does not
- [Scopes](../rag/scopes.md) — how `metadata.scopes` makes ingested rows discoverable in chat
- [Processes — react](../apps/processes.md) — `data.row.changed` on the rows a run writes
- [Observability](../operations/observability.md) — `Partial` as the status to alert on
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
