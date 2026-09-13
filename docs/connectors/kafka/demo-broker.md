# The demo broker

> One Redpanda in a container and one Python file that fills it with eleven topics over the
> same 2 400 fictional ACME partners the [REST demo API](../rest/demo-api.md) serves — the
> source every screen of the [Kafka](../kafka.md) page reads.

It ships with the repo. Two commands, and the broker is ready to be read:

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

The one dependency is the Kafka client for Python: `python3 -m pip install confluent-kafka`.
Kafka has no standard-library client, and the older `kafka-python` no longer imports on a
current Python.

## The eleven topics

Every partner has the same name and city on both pages — the record is generated from its row
number — so a screen here and a screen on the REST page show the same data through two very
different doors. Each topic is shaped for one sentence of the Kafka page:

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

The poison is placed deterministically — every index where `i % 7 == 3`, cycling through a
truncated object, a JSON array and a JSON scalar — never at random. A source whose corpus
changes between runs cannot tell a fix from a coincidence.

## The three gestures that change the broker

Everything else reads. These three write, and the Kafka page says when to use each:

```bash
python3 docs/connectors/kafka/demo/acme-topics.py feed --rate 200 --seconds 30     # a producer racing the run, on acme.live
python3 docs/connectors/kafka/demo/acme-topics.py feed --topic acme.partners-trimmed --rate 500 --seconds 2
python3 docs/connectors/kafka/demo/acme-topics.py trim 5500                        # acme.partners-trimmed: offsets 0..5499 are gone
```

`trim` does in one instant what retention does over days: it moves the partition's log start.
It goes through `rpk` inside the container, and it reads the watermark back before claiming
success — `rpk topic trim-prefix` prints a perfect-looking result table *before* asking for
confirmation, and from a non-interactive shell without `--no-confirm` it then aborts and moves
nothing. The container is named `lumnik-demo-redpanda`; set `KAFKA_DEMO_CONTAINER` to point the
command at another one.

## Checking it before you blame the manifest

```bash
python3 docs/connectors/kafka/demo/acme-topics.py verify
```

It counts every topic from the broker's own watermarks — no message is consumed — and asserts
the partition counts and that `acme.absent` still does not exist. After `trim` and `feed` the
counts have moved, so `verify` goes red on `acme.partners-trimmed`; that is the check working.
`reset` drops the eleven topics and fills them again:

```bash
python3 docs/connectors/kafka/demo/acme-topics.py reset
```

**Reset has two halves.** The offsets live in the broker; the cursor lives in the hub. A fresh
topic read with the old cursor reads nothing and calls itself caught up — green, for the wrong
reason. After `reset`, for each connector you want to read from the start again:

```bash
lm endpoint reset-cursor <connector>/default
```

!!! note "`host.docker.internal:29092`, not `localhost:19092`"
    The broker advertises two listeners because one cannot serve both worlds. Your shell reaches
    it at `localhost:19092` — that is where `acme-topics.py` produces. The hub runs in a
    container, and `localhost` inside it is that container: the manifests carry
    `host.docker.internal:29092`. A consumer handed the wrong listener connects, receives an
    address it cannot reach, and hangs until the client's metadata timeout with nothing useful in
    the error. Prove both before anything else:

    ```bash
    docker exec lumnik-demo-redpanda rpk cluster info --brokers localhost:9092
    docker exec lumnik-hub-1 bash -c 'exec 3<>/dev/tcp/host.docker.internal/29092 && echo REACHABLE'
    ```

## What it is not

- **Not a broker that lies.** The REST demo has dialects that misbehave on purpose, because an
  HTTP server can. A broker answers honestly every time; here the adversary is the **topic** —
  its partitions, its poison, its tombstones, a schema that moves, a key a column cannot be — and
  **time**: a log start that slides under a stored cursor, a producer still writing while the
  run reads.
- **Automatic topic creation is off**, so `acme.absent` stays absent. The hub's own consumer is
  built to refuse creating topics too; the broker setting is here so the demo does not depend on
  that.
- **Authenticates nobody.** Every topic is plaintext on a loopback rig. The `sasl-plain` manifest
  on the Kafka page is there for its two refusals, not for a successful handshake.
- **A throwaway rig.** One node, no replication, no persistence you should count on. Stop it
  before running the repository's own Kafka integration tests: two Redpanda instances cannot
  share one Docker VM, and the second dies before it logs a line.
