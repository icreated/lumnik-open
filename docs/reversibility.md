# Reversibility & exit

> If you stop using lumnik in two years, what do you get back, in what format — and what
> was never at risk in the first place?

## What was never at risk

**Your source system.** Stopping lumnik changes nothing in it:

- **databases** — the connection account needs `SELECT` only, and there is no write path back
  ([Architecture → Read-only first](architecture.md#read-only-first));
- **file sources** — a CSV connector's `after_process: move|archive|delete` moves or deletes files
  **on the drop zone you declared**, nothing else ([CSV & files](connectors/csv.md)).

**No vendor copy to reclaim.** Self-hosted, the data never left your infrastructure. The exit
below runs on your own machines.

## What you get back

| Asset | Format | How you take it |
|---|---|---|
| The ingested mirror | **Plain PostgreSQL 16 tables** in schemas `connector` and `ext` — ordinary columns, no application-layer encryption ([what is and isn't encrypted](security/rls.md#data-at-rest-what-is-encrypted-and-what-is-not)) | Any PostgreSQL client, or the dump below |
| Fused entities | SQL views over those tables (`connector.v_*`) | Query the views, or `lm entity export NAME -o FILE` (CSV) while the hub runs |
| The whole database | A **standard `pg_dump -Fc`**, the file the [backup script](operations/backup-restore.md) writes. Nothing schedules it: install the documented cron for a nightly one | `pg_restore` into any PostgreSQL 16+ **with the pgvector extension available**, with or without lumnik |
| Your configuration | The YAML manifests | The files you applied are in **your** repository. Everything else is in the dump, and the hub prints most of it back as YAML — see below |
| The chat corpus (Pro) | the text of each indexed chunk, in clear, with its embedding | The text is portable; see the embeddings caveat below |

Fetch the configuration back as YAML while the hub runs:

| Kind | Command |
|---|---|
| Connector | `lm connector get NAME` — its stored definition, in the hub's own field names |
| Entity | no `lm` command returns the manifest; it is in the dump |
| View | `lm view get NAME -o FILE` |
| Workflow | `lm workflow get NAME` |
| App | `lm app get NAME` |
| Export | `lm export get NAME` |
| Process | no `lm process get` — the TUI's `:processes`, `e` on the row, opens the stored manifest |
| SearchIndex | no `lm` command returns the manifest; it is in the dump |

```
$ lm entity export AcmeCustomer -o customers.csv
AcmeCustomer: 1099 bytes written to customers.csv (sha256 afa5c455ceaf9a2c6e0fae54b2fbc9b671893f22220c2c2c6a685969c0deceb5)
```

- User accounts and the audit journal are in the same database: the same dump carries them.
- Ask and chat history is not stored by the hub ([the trace is in each answer](transparency/ask-honesty.md)).

The exit artifact is the backup you already take.

## Two caveats to plan around

- **Stored secrets die with their keys.** The `lm secret` store and the connector and webhook
  credentials are encrypted with two keys from `.env`: `LUMNIK_SECRET_MASTER_KEY` and
  `LUMNIK_CRYPTO_SECRET_KEY`.
    - You can re-enter one secret at any time.
    - The two keys themselves cannot be rotated. A lost key makes the stored values
      unrecoverable ([the warning](security/rls.md#secrets-at-rest-the-master-key)).
    - For an exit, this rarely matters: the credentials point at *your* systems, and you hold
      the originals. Still, export anything you would miss before decommissioning.
- **Embeddings are model-bound** (Pro). The vectors only mean something to the embedding model
  that produced them ([Models](rag/models.md)). The chunk *text* is the portable asset: a future
  system re-embeds it with its own model.

## What leaving looks like, concretely

1. `./deploy/selfhost/backup.sh` one last time — `lumnik.dump` is your data, complete.
2. Keep the manifests you version in git. A connector made without a file (`lm source jdbc add`,
   `lm csv ingest`) is in the dump, and `lm connector get NAME` prints it while the hub still runs.
3. Restore the dump wherever your next tool reads PostgreSQL, or keep it as the archived record
   of what the mirror held. Without lumnik's database roles there, skip them:

    ```bash
    pg_restore --no-owner --no-privileges -d <your-database> lumnik.dump
    ```
4. Decommission the stack. Your source system never noticed lumnik arriving; it will not notice it
   leaving.
