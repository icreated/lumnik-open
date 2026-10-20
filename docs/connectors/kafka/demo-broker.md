# The demo broker

> One Redpanda in a container and one Python file that fills it with eleven topics over the
> same 2 400 fictional ACME partners the [REST demo API](../rest/demo-api.md) serves — the
> source every screen of the [Kafka](../kafka.md) page reads.

It ships with the repo. Two commands start and fill it:

```bash
docker compose -f docs/connectors/kafka/demo/docker-compose.yml up -d
python3 docs/connectors/kafka/demo/acme-topics.py
```

```
  ▸ created acme.partners
  ▸ created acme.orders
  ▸ created acme.partners-drift
  ▸ created acme.partners-poison
  ▸ created acme.partners-compacted
  ▸ created acme.partners-reserved
  ▸ created acme.partners-raw
  ▸ created acme.partners-trimmed
  ▸ created acme.empty
  ▸ created acme.live
  ✓ filled
  ✓ eleven topics as declared (acme.absent is absent)
```

The script needs the Kafka client for Python: `python3 -m pip install confluent-kafka`.

## The eleven topics

A partner has the same name and city here and on the REST demo. Each topic serves one section of
the Kafka page:

| Topic | Shape | Used by |
|---|---|---|
| `acme.partners` | 2 400 JSON objects, one partition | [Quickstart](../kafka.md#quickstart) |
| `acme.orders` | 24 000 orders, keyed by partner over **six** partitions | [Partitions and the cursor](../kafka.md#partitions-and-the-cursor) |
| `acme.partners-drift` | 2 400, and two new fields appear at message 1 201 | [Schema evolution](../kafka.md#schema-evolution) |
| `acme.partners-poison` | 1 000, of which **143** are not a JSON object | [Poison messages](../kafka.md#poison-messages-the-per-message-dlq) |
| `acme.partners-compacted` | `cleanup.policy=compact`; 1 000 rows then **300 tombstones** | [Tombstones](../kafka.md#tombstones-a-deletion-is-not-garbage) |
| `acme.partners-reserved` | 500, each carrying a top-level `id` — the REST record verbatim | [Seven reserved names](../kafka.md#seven-column-names-are-reserved) |
| `acme.partners-raw` | 500 with `Partner Name`, `credit.limit`, `MONTANT_€`, an object and an array | [Names the producer chose](../kafka.md#names-the-producer-chose-stay-and-stay-askable) |
| `acme.partners-trimmed` | 5 000, and `trim` moves its log start on demand | [A log that moved under the cursor](../kafka.md#a-log-that-moved-under-the-cursor) |
| `acme.empty` | created, zero messages | [An empty poll](../kafka.md#an-empty-poll-and-what-it-does-not-prove) |
| `acme.live` | empty until `feed` writes to it while a run reads | [The head is the target](../kafka.md#the-head-is-the-target) |
| `acme.absent` | **never created** | [A topic that does not exist](../kafka.md#a-topic-that-does-not-exist) |

The poison is placed deterministically (every index where `i % 7 == 3`: a truncated object, a
JSON array, a JSON scalar), so every fill gives the same counts.

## The three gestures that change the broker

These three write to the broker; the Kafka page says when to use each:

```bash
python3 docs/connectors/kafka/demo/acme-topics.py feed --rate 200 --seconds 30     # a producer racing the run, on acme.live
python3 docs/connectors/kafka/demo/acme-topics.py feed --topic acme.partners-trimmed --rate 500 --seconds 2
python3 docs/connectors/kafka/demo/acme-topics.py trim 5500                        # acme.partners-trimmed: offsets 0..5499 are gone
```

`trim` moves the partition's log start at once, as retention would over days. It runs `rpk` in
the container `lumnik-demo-redpanda`; set `KAFKA_DEMO_CONTAINER` to use another one.

## Checking it before you blame the manifest

```bash
python3 docs/connectors/kafka/demo/acme-topics.py verify
```

It checks every topic's count and partitions, and that `acme.absent` does not exist. After `trim`,
or `feed --topic acme.partners-trimmed`, `verify` reports `acme.partners-trimmed`: expected. `reset` drops the topics and fills
them again:

```bash
python3 docs/connectors/kafka/demo/acme-topics.py reset
```

**Reset the hub's cursors too.** With the old cursor, a run on a refilled topic reads nothing and
completes. After `reset`, for each connector to read from the start:

```bash
lm endpoint reset-cursor <connector>/default
```

!!! note "`host.docker.internal:29092`, not `localhost:19092`"
    - Your shell (and `acme-topics.py`) reaches the broker at `localhost:19092`.
    - The hub, in its container, reaches it at `host.docker.internal:29092` — what the manifests
      carry. The wrong listener ends in the broker timeout after about 60 s.

    Check both:

    ```bash
    docker exec lumnik-demo-redpanda rpk cluster info --brokers localhost:9092
    docker exec lumnik-hub-1 bash -c 'exec 3<>/dev/tcp/host.docker.internal/29092 && echo REACHABLE'
    ```

## What it is not

- **Automatic topic creation is off**, so `acme.absent` stays absent.
- **Authenticates nobody.** Every topic is plaintext. The `sasl-plain` manifest on the Kafka page
  shows its refusals, not a successful handshake.
- **A throwaway rig.** One node, no replication, no persistence to count on.
