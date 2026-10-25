# Kafka — when the topic misbehaves

> **lumnik Pro.** A bad message, a deletion, a missing topic, a log that moved, a broker that does
> not answer. Each case replays on [the demo broker](demo-broker.md).

## Poison messages — the per-message DLQ

`acme.partners-poison` holds 1 000 messages of which 143 are not a JSON object — a truncated
object, a JSON array, a JSON scalar:

```yaml
--8<-- "docs/connectors/kafka/demo/40-poison.yaml"
```

```
$ lm run get 4337fffd
ID        CONNECTOR          ENDPOINT  STATUS     IN    OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
4337fffd  acme-kafka-poison  default   Completed  1000  857  143      143           2026-10-24 22:35  2026-10-24 22:35
⚠ 143 rows quarantined — see: lm dlq list --run 4337fffd-5654-41a2-b32a-447a583e48ad
$ lm dlq list --run 4337fffd-5654-41a2-b32a-447a583e48ad
ID        CONNECTOR          RUN       REASON           ATTEMPTS  RESOLUTION  MESSAGE
9b85fe53  acme-kafka-poison  4337fffd  MalformedSource  1                     acme.partners-poison:0:997:997 — message value is not a JSON…
66e84e95  acme-kafka-poison  4337fffd  MalformedSource  1                     acme.partners-poison:0:990:990 — invalid JSON: Unexpected en…
9edf38bb  acme-kafka-poison  4337fffd  MalformedSource  1                     acme.partners-poison:0:983:983 — message value is not a JSON…
$ lm dlq get 9b85fe53-7f0a-4228-a645-0e88843c7d88
ID:        9b85fe53-7f0a-4228-a645-0e88843c7d88
Connector: acme-kafka-poison (9abf52e2-55a4-491d-82b6-4bde61d469b1)
Run:       4337fffd-5654-41a2-b32a-447a583e48ad
Reason:    MalformedSource
Attempts:  1
Message:   acme.partners-poison:0:997:997 — message value is not a JSON object
Detail:    {"file":"acme.partners-poison:0:997","line":997,"message":"message value is not a JSON object"}
Payload:   {"raw":["[1, 2, 3]"],"file":"acme.partners-poison:0:997","line":997}
Resolution: open
```

- `IN 1000 = OUT 857 + SKIPPED 143`, and the table holds 857 rows.
- Each entry names `topic:partition:offset` and keeps the raw value.
- A bad message is set aside once, and never read again. A long run of them stops the run early,
  `Partial`; the next run continues after them.
- The producer sent a fixed message? Once a run has read it, close the old entry:
  `lm dlq replayed <id> --new-run-id <run>`. Otherwise: `lm dlq discard <id>`.

## Tombstones — a deletion is not garbage

On a compacted topic, a record with a **null value** is how a producer says *"this key is
deleted"*; log compaction keeps it as the deletion marker. `acme.partners-compacted` holds 1 000
partners, then 300 tombstones:

```yaml
--8<-- "docs/connectors/kafka/demo/45-compacted.yaml"
```

```
$ lm run get f1005a9a
ID        CONNECTOR             ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
f1005a9a  acme-kafka-compacted  default   Completed  1300  1000  300      300           2026-10-24 22:37  2026-10-24 22:37
⚠ 300 rows quarantined — see: lm dlq list --run f1005a9a-9121-45bf-be57-b9a8ddb454e6
$ lm dlq get b931b145-2503-4ceb-be26-cd759d1d7b7b
ID:        b931b145-2503-4ceb-be26-cd759d1d7b7b
Connector: acme-kafka-compacted (845c4dc6-8ec2-429d-bf39-3fe004be76c7)
Run:       f1005a9a-9121-45bf-be57-b9a8ddb454e6
Reason:    MalformedSource
Attempts:  1
Message:   acme.partners-compacted:0:1299:1299 — Kafka tombstone (key '1299'): the message value is null — the producer deleted that key, and append-only ingestion dead-letters the marker rather than applying it as a delete
Detail:    {"file":"acme.partners-compacted:0:1299","line":1299,"message":"Kafka tombstone (key '1299'): the message value is null — the producer deleted that key, and append-only ingestion dead-letters the marker rather than applying it as a delete"}
Payload:   {"raw":[],"file":"acme.partners-compacted:0:1299","line":1299}
Resolution: open
```

- A tombstone does not delete the row. It becomes a dead letter naming the deleted **key**.
- A deletion that must reach the table travels **as data**: a field the producer sets, such as
  `deleted: true`.

## A topic that does not exist

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

## A log that moved under the cursor

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

## An empty poll, and what it does not prove

A run ends when a poll returns nothing. On a slow link with a short `poll_timeout_ms`, a poll can
time out before the first fetch and end the run with less than the topic holds.

- The run stays **`Completed`**. A green run that read less than expected: raise
  `poll_timeout_ms`. The hub log names the topic with a warning.

An empty topic, with no such line:

```
$ lm run list --connector acme-kafka-empty
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2b7a9ee6  acme-kafka-empty  default   Completed  0   0    0        0             2026-09-13 02:21  2026-09-13 02:21
```

## A broker that does not answer

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
