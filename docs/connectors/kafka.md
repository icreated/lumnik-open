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

A topic is the third kind of legacy source after files and databases: the ERP already publishes
its events, and nobody downstream has a table of them. This connector turns a topic into one —
the same tenant-isolated, askable table the CSV and JDBC connectors produce, with the same
dead-letter queue and the same run ledger — without a consumer group to provision, an offset to
commit, or a schema to declare.

## Features

- **[Hub-owned offsets, no consumer group.](#partitions-and-the-cursor)** The connector assigns
  partitions and seeks to positions it stores itself; the broker never sees a group, a commit or
  a rebalance.
- **[A run is a catch-up, not a subscription.](#the-head-is-the-target)** It measures the head of
  every partition once, drains to it and completes — on a topic that is being written to while it
  reads. A run that stopped short of its target is reported `Partial`, never `Completed`.
- **[Auto-schema, and it evolves.](#schema-evolution)** Column types are inferred from the first
  chunk; a field the producer adds later becomes a `text` column mid-run, and nested values land as
  `jsonb`.
- **[Names the producer chose are kept — and stay askable.](#names-the-producer-chose-stay-and-stay-askable)**
  `Partner Name` or `MONTANT_€` becomes a column under that exact name, and the chat quotes it.
- **[Per-message dead-letter queue.](#poison-messages-the-per-message-dlq)** A message that is not
  a JSON object is set aside with its coordinates and raw value, its offset advances, and the run
  goes on. [Tombstones](#tombstones-a-deletion-is-not-garbage) are set aside under a reason that
  names the deleted key.
- **[What the broker deleted is named.](#a-log-that-moved-under-the-cursor)** A stored position
  that retention has overtaken yields a WARN with the exact lost offset range and a `Partial` run —
  never a green run over a silent gap.
- **[A read never writes to the source.](#a-topic-that-does-not-exist)** The consumer is built with
  automatic topic creation off, so asking about a topic cannot create it; an unknown topic is
  refused by name.
- **[Credentials by name.](#auth-and-secrets)** Brokers, usernames and passwords are named, never
  written: the `lm secret` registry first, the hub's environment second, and a refusal that names
  both places it looked.
- **[Vendor presets.](#azure-event-hubs-the-event-hubs-preset)** `preset: event-hubs` expands a
  namespace and a connection string into a full SASL/SSL kafka config at apply time.
- **[Schedule and window.](#continuous-consumption)** A cron keeps the table current; a UTC window
  fences the slots; a per-endpoint lock makes overlap impossible.

## Quickstart

!!! note "Every screen on this page reads a demo broker that ships with the repo"
    One Redpanda in a container and one Python file that fills it with eleven topics over the
    same 2 400 fictional ACME partners the REST demo serves. Start it, fill it, and the broker is
    ready to be read:

    ```bash
    docker compose -f docs/connectors/kafka/demo/docker-compose.yml up -d
    python3 docs/connectors/kafka/demo/acme-topics.py        # needs: pip install confluent-kafka
    ```

    The manifests point at `host.docker.internal:29092`, because the hub runs in a container and
    `localhost` inside it is that container. Which topic carries which shape, and the three
    gestures that change the broker: [The demo broker](kafka/demo-broker.md).

```yaml
--8<-- "docs/connectors/kafka/demo/10-quickstart.yaml"
```

```
$ lm validate -f docs/connectors/kafka/demo/10-quickstart.yaml
✓ docs/connectors/kafka/demo/10-quickstart.yaml — valid
$ lm apply -f docs/connectors/kafka/demo/10-quickstart.yaml
{"name":"acme-kafka-partners","connectorId":"4cc236fc-e37f-4df7-8bc5-0173021923e7","endpointsApplied":1}
$ lm connector run acme-kafka-partners
run scheduled: c5fc42d2-87f9-4328-acd2-b29213562a54
```

"Scheduled" is not "done" — the run ledger is where the result lands, a second or two later.
Run it a second time before looking:

```
$ lm connector run acme-kafka-partners
run scheduled: 39ae9f8f-65f8-405a-a6cf-42afb1b4e7e1
$ lm run list --connector acme-kafka-partners
ID        CONNECTOR            ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
39ae9f8f  acme-kafka-partners  default   Completed  0     0     0        0             2026-09-13 02:21  2026-09-13 02:21
c5fc42d2  acme-kafka-partners  default   Completed  2400  2400  0        0             2026-09-13 02:21  2026-09-13 02:21
```

The first run read the whole topic; the second found the stored position already at the head
and read nothing. `STARTED` / `ENDED` print in **your terminal's** time zone (these screens:
Paris, UTC+2); the hub stores UTC, and `schedule` is evaluated in UTC.

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

- **Nothing was declared about the schema.** Types are inferred from the first chunk: JSON
  whole numbers land as `bigint` and numbers carrying a decimal point as `numeric(18,4)`,
  strings as `text` (timestamps included — `updated_at` is the string the producer sent),
  booleans as `boolean`, objects and arrays as `jsonb`. The columns follow the message's own
  field order.
  [Schema evolution](#schema-evolution) is what happens when the producer changes its mind.
- **Nothing was provisioned on the broker.** No consumer group exists for this connector; the
  position is a row in the hub's cursor table — [Partitions and the cursor](#partitions-and-the-cursor).
- **The table deduplicates by content.** A message read twice is one row —
  [Storage layout](#storage-layout).

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

There is no `target` block and no `mapping` block: the table is `connector.t_<name>` and the
messages land as they are. A manifest started from a jdbc or csv one gets those two keys named
at validate rather than silently stored — see [the warning below](#configuration-reference).
Three more keys exist and belong to the [Event Hubs preset](#azure-event-hubs-the-event-hubs-preset):
`preset`, `namespace`, `connection_string_env`.

### Configuration reference

`lm describe connector-type kafka` prints the schema the validator enforces — type, importance
and the one-line meaning of every key, nested blocks included:

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
| `brokers` / `brokers_env` | — | exactly one of the two must be present; `brokers_env` is a name the hub resolves when the run starts |
| `topic` | — | must match `[a-zA-Z0-9._-]+` |
| `format` | `json` | the only accepted value; Avro and Protobuf are [not yet supported](#limitations) |
| `poll_timeout_ms` | `2000` | integer in `[100, 60000]` |
| `max_poll_records` | `500` | integer ≥ 1; a value above the chunk size is clamped to it — see [Chunks](#chunks-and-the-two-poll-knobs) |
| `auth.kind` | `none` | `none` or `sasl-plain`; `sasl-plain` requires `username_env` and `password_env`, and refuses a blank one |
| `auth.security_protocol` | `SASL_SSL` | lands verbatim in the consumer's `security.protocol`; it is not validated at apply |
| `schedule` | — | five-field cron, validated at apply, evaluated in UTC |
| `window` | — | `HH:MM-HH:MM`, UTC, end exclusive, may cross midnight; a manual run ignores it |
| `metadata.scopes` | — | the métier scope(s) the table is askable in; the validator warns when none is declared |

Every rule above is enforced at `lm validate` and again at `lm apply`, so a manifest the
command blesses is one the hub accepts. Five mistakes in one file, all named at once:

```yaml
--8<-- "docs/connectors/kafka/demo/90-refused.yaml"
```

```
$ lm validate -f docs/connectors/kafka/demo/90-refused.yaml
✗ docs/connectors/kafka/demo/90-refused.yaml — 5 error(s), 1 warning(s)

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

The warning is the one worth reading twice. **The key list is closed**: a key the connector reads
nowhere — `poll_timeout` for `poll_timeout_ms`, `target` or `mapping` copied from another
manifest — is named rather than accepted and stored. It stays a warning, the manifest still
applies, but `poll_timeout: 50` would otherwise read back from `lm connector get` as a 50 ms
poll on a connector that polls for 2 000. The sweep reaches inside `auth`, where a misspelled
`security_protocol` leaves the default `SASL_SSL` in place and a manifest meant for a plaintext
SASL broker dies in a TLS handshake with the typo as the cause.

## How a run reads a topic

### Partitions and the cursor

A Kafka topic is one or more partitions, each an ordered log where every message has an
**offset**. A consumer's position is therefore not one number but one per partition — and that is
exactly what the hub stores. `acme.orders` is keyed by partner over six partitions:

```yaml
--8<-- "docs/connectors/kafka/demo/20-six-partitions.yaml"
```

```
$ lm run list --connector acme-kafka-orders
ID        CONNECTOR          ENDPOINT  STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
3463bf93  acme-kafka-orders  default   Completed  24000  24000  0        0             2026-09-13 02:21  2026-09-13 02:21
```

The position after that run, read out of `connector.connector_cursor` — the same table that
holds a JDBC watermark or a CSV file position:

```
{"type": "KafkaOffset", "offsets": {"0": 3880, "1": 3770, "2": 3930, "3": 4120, "4": 4190, "5": 4110}}
```

Six keys, one per partition, each the **next offset to read**; they sum to 24 000. The next run
seeks every partition to its stored offset and continues from there. A partition with no stored
offset — the first run, or a partition the topic gained since — is read from its beginning, so
**run #1 reads everything the topic still retains**.

What the broker does *not* see: no `group.id`, no offset commit, no rebalance. The consumer is
built with `enable.auto.commit=false`, assigns its partitions and seeks explicitly, and is closed
after every chunk. Nothing has to be provisioned or cleaned up on the Kafka side, which is also
why one connector cannot trample another's position — each has its own cursor row.

### The head is the target

When a run starts, the hub asks the broker for the **head** of every partition (the high
watermark) and writes it to the log:

```
INFO  run 3463bf93-… target: partition offsets [0: 3880, 1: 3770, 2: 3930, 3: 4120, 4: 4190, 5: 4110]
```

That is the run's whole definition of *done*: read from the stored position to those offsets,
then complete. A message produced while the run is reading belongs to the **next** run. On a
topic that is written to continuously — which is every real topic — this is what makes a run
end at all:

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

The first run found 1 313 messages at its start, read exactly those in 270 ms and completed
while the feeder was still writing. The second read the 2 673 that had arrived since. Together,
3 986 — the feeder's own count — and no row twice.

A run that ends **below** the target it gave itself is reported **`Partial`**, not `Completed`.
It has two causes, both certain: the run began by skipping offsets its stored position was
entitled to because the broker had already deleted them ([below](#a-log-that-moved-under-the-cursor)),
or the reader stopped on its own chunk budget while the target said there was more (a topic
feeding only unparsable messages, [below](#poison-messages-the-per-message-dlq)). Being below the
head is not itself partial: the head is an *upper* bound on readable records — a transactional
producer leaves a commit marker there, compaction leaves gaps — so a run that read everything
readable can end an offset short of it, correctly. `Partial` is the status worth alerting on:
the run wrote everything it read, kept its position, and simply did not read everything there
was; see [Observability](../operations/observability.md).

### Chunks and the two poll knobs

Every connector run writes in **500-row chunks**, each in its own transaction with the cursor
persisted beside it. On kafka a chunk is filled by consumer polls, and the two knobs shape them:

- `max_poll_records` is how many records one poll may return. The default is the chunk size, and
  a larger value is clamped to it — `5000` still gives 6 chunks for 2 400 messages, exactly like
  the default. A smaller one gives more chunks and more transactions: `100` gives 25. Measured on
  `acme.partners`: 6 chunks in 865 ms against 25 in 1 042 ms.
- `poll_timeout_ms` is how long one poll waits for the broker. A fresh consumer is built for
  every chunk, so the first poll of a run pays metadata, connection and fetch out of that budget.
  The default of 2 000 ms is generous for a broker on the same network; the floor of 100 ms is
  legal and, on a slow link, is how a run [reads nothing and ends](#an-empty-poll-and-what-it-does-not-prove).

A second run on a topic that has not moved does not poll at all — the stored position already
sits at the target, so it completes in a few tens of milliseconds with `IN 0`.

### Continuous consumption

To keep the table current, declare a `schedule`. Each slot is a fresh catch-up from the stored
cursor to the head of that moment:

```yaml
--8<-- "docs/connectors/kafka/demo/30-cadence.yaml"
```

```
$ lm apply -f docs/connectors/kafka/demo/30-cadence.yaml
{"name":"acme-kafka-cadence","connectorId":"973926e1-8936-44ab-918c-3fb06f05b64d","endpointsApplied":1}
$ lm connector list
NAME                         TYPE          TENANT  SCHED        WINDOW  STATUS     LAST RUN
acme-kafka-cadence           kafka         1       */5 * * * *                     
acme-kafka-partners          kafka         1                            Completed  2026-09-13 02:21
```

- **Evaluated in UTC** by the hub's own scheduler — no OS cron. A re-apply without `schedule`
  unschedules the connector.
- **Overlap is impossible.** A slot that fires while a run is still active on the endpoint is
  skipped (per-endpoint advisory lock) and recorded as `Cancelled` with the reason. Because a run
  drains to the head it found and completes, a busy topic does not hold the lock for ever: an
  aggressive cadence degrades to back-to-back catch-ups, never to double ingestion.
- **`window` fences the slots.** `"06:00-22:00"` skips — never queues — a slot due at night; the
  end is exclusive, and the range may cross midnight (`"22:00-06:00"`). The fence gates a run's
  *start*: a run that began inside the window drains to the head it found and completes. A manual
  `lm connector run` always ignores it — an operator asking explicitly is not the scheduler.
- An external trigger works too: your own cron plus `lm connector run <name>`.

**One connector reads one topic.** There is no message-key or header filtering: whatever the
topic carries lands whole in that connector's table. A topic that multiplexes several event types
needs one connector per topic on the producer side — or a second connector on the same topic
with a different `name`, if the same messages must land twice.

## Storage layout

Nothing to set up: the table is created by the first run that has a row to write. This section
is reference, for when you need it.

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
    "t_acme_partners__row_hash_key" UNIQUE CONSTRAINT, btree (_row_hash)
    "t_acme_partners_idx_tenant_id" btree (tenant_id)
Policies:
    POLICY "tenant_isolation"
      USING ((tenant_id = (NULLIF(current_setting('app.current_tenant'::text, true), ''::text))::bigint))
```

- **The seven columns above the line** are the hub's: `id`, `_row_hash`, `_ingested_at`,
  `_source`, `_run_id`, `_mapping_version`, `tenant_id` — the same seven every `connector.t_*`
  table carries. The rest came from the messages, in the order the message lists its fields.
- **`_row_hash` is the dedup.** It is a hash of the row's content, `UNIQUE`, and a kafka write is
  `ON CONFLICT DO NOTHING`: a message read twice — a producer retry, a cursor reset — is one
  row. `data.row.changed` events, where a [Process](../apps/processes.md), a webhook or a
  notification rule subscribes to the table, fire for genuinely new rows only.
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

    `OUT 2400`, and the table still holds exactly 2 400 rows. The re-read is a no-op the ledger
    reports as a full write, the same way every connector family reports a re-run; the witness
    that nothing duplicated is the table's row count, not the `SKIPPED` column.

!!! warning "Deleting a kafka connector deletes its data"
    `lm connector delete` drops `connector.t_<name>` and the cursor — the same erasure gesture as
    the other connector families (a kafka run writes rows and the schema card, not RAG chunks, so
    there is nothing else to drop). The topic is untouched: the hub never writes to it, and
    re-applying the manifest reads it again from the beginning.

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

The table was created from the first chunk's six fields; when a message carrying `vat_number`
arrived in a later chunk, the column was added (`ALTER TABLE ADD COLUMN`) and the run went on.
Rows written before it existed hold `NULL` there — nothing was re-read and nothing was lost. A
field that *disappears* from later messages simply leaves its column `NULL` for those rows.

**The new column is typed like any other**: from the chunk that introduces it, by the same
inference the first chunk uses. A whole number arriving on day two lands `bigint`, one with a
decimal point `numeric`, a boolean `boolean`, a nested value `jsonb`. The two fields above are
strings, so the screen cannot show the difference — but a `credit_limit` appearing later would.
The added columns come after the ones created first, in the order their chunk presents them.

The bound is the chunk, exactly as it is at creation: inference reads the messages in hand, so a
field that is present but `null` in all of them still lands `text`. Each run that adds columns
says so in the hub log, naming them and the type each one got, because a decision taken from a
partial sample is one you may want to revisit — the gesture for that is `lm endpoint reset-cursor`
followed by a re-read, once the producer has settled.

Each run that changed the table's shape refreshes the scope's schema card, so the chat knows the
new column before the next question is asked.

### Names the producer chose stay — and stay askable

A kafka manifest has **no `mapping` block** — no hooks run at ingestion, nothing renames,
flattens or filters — so the field names on the topic are the column names in the table.
`acme.partners-raw` carries the shapes an ERP export is fond of:

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

A space, a dot, a currency sign, a capital — each is a quoted identifier in PostgreSQL, and the
hub quotes it everywhere it generates SQL. The schema card the chat reads names every column
quoted too, so generated SQL quotes it as well:

```
$ lm ask --scope billing "How many rows are in connector.t_acme_partners_raw and what is the largest MONTANT_€?"
There are 500 rows in connector.t_acme_partners_raw. The largest MONTANT_€ is 748.5000.
  data as of: 2026-09-13T00:23:26.656851Z (last completed run)

  SQL: SELECT COUNT(*), MAX("MONTANT_€") AS max_montant_eur FROM "connector"."t_acme_partners_raw"
```

The nested `address` and the `tags` array landed as `jsonb`, values intact. One thing does not
survive: **object key order** — `{"street", "city"}` was produced and `{"city", "street"}` came
back, because `jsonb` stores an object as a set of keys. Order that matters travels in an array.
A [`kind:Workflow`](../apps/workflows.md#names-the-source-chose) lifecycle can be declared over
such a column just as it is; see also
[Names the source chose](../transparency/ask-honesty.md#names-the-source-chose).

### Seven column names are reserved

`id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id` — the
hub adds these to every `connector.t_<name>` table. A top-level JSON field sharing one of them is
refused at write time, on a brand-new table and on an existing one alike. `id` is the one you
will meet: `acme.partners-reserved` carries the REST demo's partner record verbatim, `id` included.

```yaml
--8<-- "docs/connectors/kafka/demo/58-reserved.yaml"
```

```
$ lm run get 7ebc090f
ID        CONNECTOR            ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
7ebc090f  acme-kafka-reserved  default   Failed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
✖ run failed: source column 'id' collides with a hub system column (_ingested_at, _mapping_version, _row_hash, _run_id, _source, id, tenant_id); it must reach the hub under another name — jdbc: source.tables[].rename; csv: a map-columns row hook under spec.mapping; kafka: no rename exists, the field must be renamed in the topic payload
```

Because there is no mapping block, there is **no in-product rename**: the field has to reach the
hub under another name, which means changing what the producer puts on the topic.

## When the topic misbehaves

A broker does not lie the way an HTTP server can — it answers honestly every time. What
misbehaves is the **topic**: a message that is not what the schema expects, a deletion, a name
that does not exist, and **time**: a log start that moved under the stored position, a poll that
came back empty for the wrong reason.

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

- **The conservation identity holds by hand**: `IN 1000 = OUT 857 + SKIPPED 143`, and the table
  holds 857 rows. A poison message is read, counted, set aside — never silently dropped.
- **The coordinate is `topic:partition:offset`**, and the raw value travels with it (capped at
  8 192 characters), so the message can be found on the broker and read.
- **Offsets still advance.** A poison message is dead-lettered once and never re-read: the next
  run on this topic reads `IN 0`. A poll of *only* poison keeps polling for a real row, bounded
  by the chunk budget, so a continuously-poison topic cannot wedge a run — the reader stops on
  its budget, the run ends `Partial` with the dead-lettered offsets persisted, and the next run
  resumes past them.
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

lumnik ingestion is **append-only** — the hub mirrors what the topic carried, it never removes a
row behind your back — so a tombstone becomes one DLQ entry naming the **key** it deleted, which
is all a tombstone carries. It counts as a skip (`1300 = 1000 + 300`), it does not block, and the
1 000 rows around it landed in the same run. If those deletions matter to your table, the
deletion has to reach the hub **as data** — a field the producer sets — because there is no
mapping hook here to interpret one.

### A topic that does not exist

```yaml
--8<-- "docs/connectors/kafka/demo/60-absent.yaml"
```

```
$ lm run get c1008671
ID        CONNECTOR          ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
c1008671  acme-kafka-absent  default   Failed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
✖ run failed: kafka topic 'acme.absent' does not exist on the broker: it reports no partition for that name, so there is nothing to read. Check spec.source.topic for a typo, or create the topic on the broker. The hub never creates one (an ingestion run must not write to its source).
```

Two things happened in those 36 ms. The consumer asked the broker about `acme.absent` **with
automatic topic creation off** — the client's default is *on*, and a broker left at Apache's own
default would have created the topic and handed back one empty partition, turning a typo into a
green run over zero rows and a write into your cluster. And the empty answer was refused **by
name**, with the cause and the gesture, instead of the client's own
`Consumer is not subscribed to any topics or assigned any partitions`.

The message says which of two situations it is. A first read is a name to check. An endpoint
that **has** a stored position read this topic before — the topic was deleted or renamed on the
broker, or the manifest's topic name was edited — and the message says so instead of sending you
to hunt a typo in a manifest that worked for months.

### A log that moved under the cursor

Retention, compaction or an administrative trim moves a partition's log start forward. A stored
position below it names messages that no longer exist. The Kafka client repairs such a seek by
itself (`auto.offset.reset=earliest`) and says nothing — which is how a run that lost 500
messages would look exactly like one that read everything. Replay it on the demo broker:

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
$ lm run get 49b90bd6
• run partial: acme.partners-trimmed[0]: the stored position is offset 5000 but the broker's log now starts at 5500 — offsets 5000..5499 are no longer on the broker (retention, compaction or a trim removed them) and can never be read; resuming at 5500
```

Run 1 read 5 000 and stored position 5 000. The producer wrote 607 more; the broker deleted
everything below 5 500. Run 2 asked the broker where the log starts **before** seeking, saw its
position overtaken, read the 107 messages that remained — and reported itself **`Partial`**, with
the exact lost range in its message and the same sentence at `WARN` in the hub log. Nothing
recovers those 500 messages; no connector could. What changed is that a run that read less than
the topic ever held no longer looks like a complete one.

A first run against a topic whose start has already moved is different: nothing was lost *here*,
and the run completes with an `INFO` line saying which offsets left the broker before this
connector ever read it. `lm endpoint reset-cursor` deletes the stored position, so a reset
endpoint is honestly a first read again.

The gesture, when it repeats: raise the topic's retention, or shorten the interval between runs.

### An empty poll, and what it does not prove

A run ends when a poll returns nothing — that is how "caught up" is detected. A poll that
**timed out** before its first fetch arrived looks identical, and a fresh consumer per chunk
means the first poll of a run pays metadata, connection and fetch out of `poll_timeout_ms`. On a
slow link at the 100 ms floor, a run can read nothing from a topic holding thousands of messages
and end.

Because the run knows its target, that silence is named. A poll that returns nothing while the
position is still below the head ends the run on a `WARN` stating both numbers:

```
WARN  acme.orders: the poll returned no record within 100 ms and the position is below the high watermark on partition(s) [0: at 0, high watermark 3880, …] — the poll may have timed out before the first fetch arrived, or those offsets may hold no readable record (compaction, transaction markers). The run ends here either way. Raise poll_timeout_ms if this repeats on a topic known to have data
```

It names two causes and chooses neither, because it cannot: compaction gaps and transaction
markers occupy offsets no consumer is ever handed, so a run that read everything can end below
the watermark, correctly. The run stays `Completed` — this is the one shortfall the hub cannot
prove — and the message goes to the hub's log only. When a run ingested less than you expected
and the ledger is green, read the log for the topic's name.

An honest empty topic looks like this, in 46 ms and with no such line:

```
$ lm run list --connector acme-kafka-empty
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2b7a9ee6  acme-kafka-empty  default   Completed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
```

### A broker that does not answer

A bootstrap address that answers nothing fails the run on the Kafka client's own metadata
timeout — one wait of about **60 s**, then a `Failed` row saying the broker did not answer,
naming the address and the topic. The run measures its target first; when *that* is what times
out, the reader is not given the same 60 s to spend over again, because a broker that did not
answer a metadata request will not answer a fetch either. An address that refuses the connection
fails the same way. Neither is reported as a successful empty sync, and the failure is marked
retryable — a broker that comes back is what the next scheduled slot finds.

## Auth and secrets

| `auth.kind` | Config | Wire |
|---|---|---|
| `none` | nothing — the default when `auth:` is omitted | plaintext, no SASL handshake |
| `sasl-plain` | `username_env` + `password_env`, optional `security_protocol` | SASL/PLAIN over `SASL_SSL` (default) or `SASL_PLAINTEXT` |

Every `*_env` key on this connector — `brokers_env`, `username_env`, `password_env` — is a
**name**. The hub resolves it when the run starts: the [`lm secret`](../admin-console-guide.md)
registry for your tenant first, then its own environment (an OS variable, then a JVM system
property). The value is never in the manifest and never in the stored config.

```yaml
--8<-- "docs/connectors/kafka/demo/62-brokers-env.yaml"
```

Run it before the name resolves, and the refusal names **both** places it looked:

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

The same resolution serves the credentials. `sasl-plain` refuses a name that resolves to nothing
— or to blanks — **before** opening a connection, so the failure is about the secret and not
about SASL:

```yaml
--8<-- "docs/connectors/kafka/demo/65-sasl.yaml"
```

```
$ lm run get 052f113a
✖ run failed: 'ACME_KAFKA_USER' (username_env) resolves to nothing — no 'lm secret' entry for this tenant, and nothing of that name in the hub's own environment
```

The demo broker authenticates nobody, so once both names resolve the *broker* refuses, in its
own words — the message to expect from a listener that does not speak the mechanism you declared:

```
$ lm run get 9e962b07
✖ run failed: Unexpected handshake request with client mechanism PLAIN, enabled mechanisms are []
```

- `security_protocol` lands verbatim in the consumer's `security.protocol`; it is not validated
  at apply. `SASL_SSL` is the default; a SASL listener without TLS wants `SASL_PLAINTEXT`.
- The username and password are embedded in the JAAS login string with proper escaping — a secret
  containing a backslash or a double quote cannot break the parser or smuggle an extra option.
- SASL/SCRAM, mTLS and OAuth are [not yet supported](#limitations).

## Azure Event Hubs — the `event-hubs` preset

Event Hubs exposes a Kafka-compatible endpoint that authenticates as SASL/PLAIN with the **fixed
username `$ConnectionString`** (the literal string, dollar sign included — it is not a secret) and
the namespace connection string as the password. The preset writes that config for you:
`connector_type` stays `kafka`, and `spec.preset` rides as an internal field the apply handler
expands and removes.

```yaml
--8<-- "docs/connectors/kafka/20-event-hubs.yaml"
```

```
$ lm apply -f docs/connectors/kafka/20-event-hubs.yaml
{"name":"event-hubs-telemetry","connectorId":"6ba84373-8816-4ef2-98dd-6b98477c924e","endpointsApplied":1}
$ lm connector get event-hubs-telemetry
name: event-hubs-telemetry
typeId: kafka
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
```

The stored config is a plain kafka one: `preset`, `namespace` and `connection_string_env` did
their work at apply time and do not survive. The two names it reads resolve like every other
`*_env` key — registry first, then the hub's environment:

```bash
lm secret set EVENTHUBS_CONN_STR          # the namespace Primary Connection String, Endpoint=sb://…
lm secret set EVENT_HUBS_SASL_USERNAME    # the fixed literal $ConnectionString
```

An unknown `preset` name is refused at validate (`unknown source preset 'event-hubbs'`) — a typo
never silently degrades to plain kafka — and a preset missing an input reports each missing key
by name (`preset 'event-hubs' requires 'namespace'`) before anything is expanded. No consumer
group is created on the Event Hubs side either; there is nothing to provision or clean up there.

## Limitations

- **JSON only.** One JSON object per message value. Avro, Protobuf and a Schema Registry are not
  yet supported; `format` accepts only `json`.
- **One topic per connector**, and no filtering by message key or header: whatever the topic
  carries lands in the table. Message keys, headers and timestamps are not stored as columns.
- **No mapping block.** No hooks run at ingestion — no rename, no flatten, no filter, no lookup.
  A field must reach the hub under the name and shape it should land with.
- **No pre-flight.** `lm source jdbc test` and `lm source rest test` have no kafka sibling yet;
  the first run is the test. It is a cheap one: a topic that does not exist is refused in tens of
  milliseconds, an unresolved secret before any connection.
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

**Result:** every five minutes, a catch-up from the stored cursor to the head of that moment;
slots at night are skipped. A slot that meets a running run is skipped too, so the cadence can be
as tight as you like. See [Continuous consumption](#continuous-consumption).

### Read a topic again from the beginning

```bash
lm endpoint reset-cursor <connector>/default
lm connector run <connector>
```

**Result:** the stored position is deleted and the run reads everything the topic retains. Rows
already in the table are refused by `_row_hash` and counted in `OUT` anyway — the table's row
count is the witness. The command prints nothing and exits 0.

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
