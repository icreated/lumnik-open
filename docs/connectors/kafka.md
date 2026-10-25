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
| **Position** | kept by the hub, per partition — nothing to set up on the broker |
| **Delivery** | a message read twice lands once |
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
Applied connector "acme-kafka-partners" — 1 endpoint
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

- **No schema to declare.** Each column takes its type from the first messages that carry the field:

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
  max_poll_records: 500             # optional; leave it at its default
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

`lm describe connector-type kafka` prints every key with its meaning. The rules:

| Key | Default | Rule |
|---|---|---|
| `name` | — | a plain identifier: letters, digits, underscore, not starting with a digit — it becomes `connector.t_<name>` |
| `brokers` / `brokers_env` | — | at least one is required; when both are set, `brokers` is used. `brokers_env` is a name the hub resolves when the run starts |
| `topic` | — | must match `[a-zA-Z0-9._-]+` |
| `format` | `json` | the only accepted value; Avro and Protobuf are [not yet supported](#limitations) |
| `poll_timeout_ms` | `2000` | integer in `[100, 60000]` |
| `max_poll_records` | `500` | integer ≥ 1; leave it at its default |
| `auth.kind` | `none` | `none` or `sasl-plain`; `sasl-plain` requires `username_env` and `password_env`, and refuses a blank one |
| `auth.security_protocol` | `SASL_SSL` | `SASL_SSL` or `SASL_PLAINTEXT`. A wrong value is not caught by `lm validate`: the run fails when it connects |
| `schedule` | — | five-field cron, validated at apply, evaluated in UTC |
| `window` | — | `HH:MM-HH:MM`, UTC, end exclusive, may cross midnight; a manual run ignores it |
| `metadata.scopes` | — | the business scope(s) the table is askable in; the validator warns when none is declared |

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

- A misspelled key (`poll_timeout`), or a `target` copied from another manifest, is named and
  ignored; the manifest still applies.
- A `mapping` block is **refused**: a check declared there would never run.
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

- Each partition keeps its own position. The next run starts where each one stopped.
- The first run reads everything the topic still retains. A partition added later is read
  from its beginning.
- Nothing is set up on the broker: no consumer group, and each connector keeps its own position.

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
`Completed` — see [An empty poll](kafka/when-the-topic-misbehaves.md#an-empty-poll-and-what-it-does-not-prove)):

- the broker had already deleted offsets the stored position pointed to
  ([A log that moved under the cursor](kafka/when-the-topic-misbehaves.md#a-log-that-moved-under-the-cursor));
- a long run of unreadable messages stopped the run early; the next run continues after them
  ([Poison messages](kafka/when-the-topic-misbehaves.md#poison-messages-the-per-message-dlq)).

`Partial` is the status to alert on — see [Observability](../operations/observability.md).

A run on a topic that has not moved completes at once with `IN 0`.

### Continuous consumption

To keep the table current, declare a `schedule`. Each slot is a fresh catch-up from the stored
cursor to the head of that moment:

```yaml
--8<-- "docs/connectors/kafka/demo/30-cadence.yaml"
```

```
$ lm apply -f docs/connectors/kafka/demo/30-cadence.yaml
Applied connector "acme-kafka-cadence" — 1 endpoint
$ lm connector list
NAME                TYPE   TENANT  SCHED        WINDOW       STATUS  LAST RUN
acme-kafka-cadence  kafka  1       */5 * * * *  06:00-22:00
```

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

Nothing to set up: the first run with a row to write creates `connector.t_<name>`.

- Each top-level JSON field is a column, in the message's order.
- **A message read twice is one row**: a producer retry, or a re-read after
  `lm endpoint reset-cursor`. Processes and webhooks fire for new rows only.
- Each tenant sees only its own rows.

`OUT` counts every message read, even one the table already held. Re-read the whole topic, then
compare with the table's row count:

```
$ lm endpoint reset-cursor acme-kafka-partners/default
$ lm connector run acme-kafka-partners
$ lm run list --connector acme-kafka-partners
ID        CONNECTOR            ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
39b5d5bd  acme-kafka-partners  default   Completed  2400  2400  0        0             2026-10-24 22:36  2026-10-24 22:36
b80f7cda  acme-kafka-partners  default   Completed  2400  2400  0        0             2026-10-24 22:35  2026-10-24 22:35
$ lm data tables
MAPPING                               SCHEMA     TABLE                      ROWS
kafka:acme_partners                   connector  t_acme_partners            2400
```

`OUT 2400` twice, and the table still holds 2 400 rows.

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
- **The new column takes its type from the first messages that carry it.** If they all hold
  `null`, it lands as text.
- **A column keeps its type.** To change it once the producer has settled: delete the connector,
  apply it again, run. The table is created again.
- The chat knows the new column after the run (Pro).

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

`address` and `tags` landed as `jsonb`, values intact. The order of an object's keys is not
kept: `{"street", "city"}` comes back `{"city", "street"}`.

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

A bad message, a deletion, a missing topic, a log that moved, a broker that does not answer:
each case, as `lm` shows it, in [When the topic misbehaves](kafka/when-the-topic-misbehaves.md).

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
Applied connector "event-hubs-telemetry" — 1 endpoint
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

Apply a second manifest with the same `topic` and a different `name`. Each connector keeps its
own position and its own table.

**Result:** two connectors over one topic, useful when two teams need the same events under
two scopes.

### Move to Event Hubs

Replace `brokers` and `auth` with `preset: event-hubs`, `namespace` and `connection_string_env`,
and set the two secrets. **Result:** the same run ledger, the same table; see
[the preset](#azure-event-hubs-the-event-hubs-preset).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| first run ingests far more than expected | no stored position yet — a full-retention read, by design | let it complete; later runs are incremental |
| a green run read less than expected | the broker answered slowly | raise `poll_timeout_ms` |
| a run ends `Partial` | the broker deleted messages before they were read, or a long run of unreadable messages stopped the run | `lm run get` names which. Deleted messages cannot be recovered: raise the topic's retention, or run more often |
| run fails: `kafka topic '…' does not exist on the broker` | no partition reported under that name, and the hub never creates one | first read: check `spec.topic` or create the topic; stored position: the topic was deleted or renamed on the broker |
| run fails: `'NAME' (brokers_env) resolves to nothing` | neither the registry nor the hub's environment holds it | `lm secret set NAME`, or export it where the hub runs |
| run fails: `Unexpected handshake request with client mechanism PLAIN` | the listener does not speak SASL/PLAIN — wrong port, or `auth` on a plaintext broker | check the broker's listener; drop `auth` for a plaintext one |
| run fails after ~60 s: `the kafka broker did not answer within the client's metadata timeout` | the bootstrap address answers nothing — wrong host, wrong listener, or a broker that is down | from the hub's container, prove the address; on Docker, `host.docker.internal`, not `localhost` |
| run fails at once: `No resolvable bootstrap urls given in bootstrap.servers` | the hub cannot resolve the broker host name (e.g. `host.docker.internal` on a hub not running in Docker) | use a host name the hub resolves |
| run fails: `connector.t_… is already written by connector '…'` | another connector of the tenant already writes `connector.t_<name>` | give this endpoint another `name` — see [JDBC](jdbc.md#two-connectors-reading-a-table-of-the-same-name) |
| run fails: `source column 'id' collides with a hub system column` | a top-level field shares a reserved name (`id`, `tenant_id`…) | the producer must emit it under another name — no rename exists on kafka |
| messages missing from the table, `DEAD-LETTERS` > 0 | not a JSON object — dead-lettered per message, offsets advance | `lm dlq list --run <id>`; fix the producer and re-send, then `lm dlq replayed ID --new-run-id RUN` (the run that read it again) or `discard` |
| rows missing, `lm dlq get` says `a Kafka tombstone (key '…')` | the producer deleted that key; ingestion is append-only | nothing to repair — if deletions must reach the table, send them as data |
| a scheduled slot shows `Cancelled: skipped: another run is already active` | the previous catch-up was still running | nothing — the next slot catches up; widen the cadence if it repeats |
| a column is `NULL` for the first N rows and filled after | the producer added the field mid-topic — [schema evolution](#schema-evolution) | expected: the early messages never carried it |
| `lm validate` warns `unknown key` on `target` | copied from a jdbc or csv manifest; kafka has none | remove the block |
| `lm validate` refuses `spec.mapping`: `kafka has no mapping block` | copied from a csv manifest; no hook runs on kafka | remove the block; rename the field in the topic's payload |
| `unknown connector type: kafka` | the hub is the open edition | Kafka is a Pro connector |

## See also

- [The demo broker](kafka/demo-broker.md) — the source every screen here reads
- [Overview](index.md) — apply/validate flow shared by all connectors
- [Transformers](transformers.md) — the hooks other connectors run at ingestion, and that kafka does not
- [Scopes](../rag/scopes.md) — how `metadata.scopes` makes ingested rows discoverable in chat
- [Processes — react](../apps/processes.md) — `data.row.changed` on the rows a run writes
- [Observability](../operations/observability.md) — `Partial` as the status to alert on
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
