# Reversibility & exit

> If you stop using lumnik in two years, what do you get back, in what format — and what
> was never at risk in the first place?

Every purchasing committee asks this. The answer deserves a page instead of a scavenger
hunt.

## What was never at risk

**Your source system.** lumnik is a read-only mirror of your source *databases*: the connection
account needs `SELECT` and nothing more, and there is no write path back to them — not
behind a flag, not for administrators ([Architecture → Read-only first](architecture.md)).
The one deliberate exception is file sources: a CSV connector's `after_process:
move|archive|delete` does move or delete files **on the drop zone you declared** — never
in a database ([CSV & files](connectors/csv.md)). Stopping lumnik changes nothing about
the system of record, because lumnik never was one.

**And there is no vendor copy to reclaim.** Self-hosted means the data never left your
infrastructure: there is nothing at a provider's to demand restitution or destruction of.
The exit below is entirely an operation on your own machines.

## What you get back

| Asset | Format | How you take it |
|---|---|---|
| The ingested mirror | **Plain PostgreSQL 16 tables** in schema `connector` — ordinary columns, no application-layer encryption ([what is and isn't encrypted](security/rls.md#data-at-rest-what-is-encrypted-and-what-is-not)) | Any PostgreSQL client, or the dump below |
| Fused entities | SQL views over those tables, also served as JSON (`GET /api/entities/{name}`) | Query the views, or call the API while the hub still runs |
| The whole database | A **standard `pg_dump -Fc`** — the same artifact the [backup script](operations/backup-restore.md) produces (nightly once you install the documented cron; nothing schedules it for you) | `pg_restore` into any PostgreSQL 16+ **with the pgvector extension available** (the `rag` schema needs it — or exclude that schema on restore), with or without lumnik |
| Your configuration | The YAML manifests (connectors, entities, views, processes, workflows) | Manifests you applied from files stay in **your** repository; config created through the guided flows (`lm source jdbc add`, the TUI editor, applied views/workflows) lives in the hub's database — and is therefore inside the dump above, and fetchable back as YAML while the hub runs (`lm workflow get NAME`, `lm view get NAME -o FILE` — see the [CLI reference](cli/lm-reference.md)) |
| The RAG corpus | `rag.chunk` rows: chunk text in clear + pgvector embeddings | The text is portable; see the embeddings caveat below |

User accounts and the audit journal live in the same database — the same dump carries
them. Ask/chat history is not stored server-side at all ([the trace is in each
response](transparency/ask-honesty.md)). No proprietary formats, no export tooling to
buy, no data hostage: the exit artifact is the backup you should already be taking.

## Two caveats to plan around

- **Stored secrets die with the master key.** Connector/webhook credentials and tenant
  secrets are pgcrypto-encrypted under `LUMNIK_SECRET_MASTER_KEY` /
  `LUMNIK_CRYPTO_SECRET_KEY`; individual secrets can be re-entered (rotated) at any time,
  but the **master keys** have no rotation or re-encryption tooling, and a lost key makes
  the stored values unrecoverable ([the warning](security/rls.md#secrets-at-rest-the-master-key)).
  For an exit this is usually irrelevant — the credentials point at *your* systems and you
  hold the originals — but export anything you'd miss before decommissioning.
- **Embeddings are model-bound.** The vectors in `rag.chunk` only mean something to the
  embedding model that produced them ([Models](rag/models.md)). Treat the chunk *text* as
  the portable asset; a future system re-embeds it with its own model.

## What leaving looks like, concretely

1. `./deploy/selfhost/backup.sh` one last time — `lumnik.dump` is your data, complete.
2. Keep the manifests you version in git where they live; guided-flow config is in the
   dump (or fetch it back as YAML while the hub still runs).
3. `pg_restore` the dump wherever your next tool reads PostgreSQL — or just keep it as
   the archived, queryable record of what the mirror held.
4. Decommission the stack. Your source system never noticed lumnik arriving; it will not notice it
   leaving.

There is no lock-in mechanism to negotiate around. That is by design.
