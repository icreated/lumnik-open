# CSV connector — local use-case library

This directory contains runnable `lm apply` manifests that cover the most common
local-file CSV patterns.  Each YAML file is both a working example and a corpus
entry for the E2E suite — they must not rot.

---

## Quick start

```bash
# apply (register) a connector, then run it once
lm apply -f docs/connectors/csv/10-local-simple.yaml
lm connector run csv-local-simple
```

---

## Files at a glance

| File | What it demonstrates |
|------|----------------------|
| `10-local-simple.yaml` | Minimal config: one local file, full load on every run, no cursor. |
| `20-local-glob-incremental.yaml` | Glob path matches multiple files; fixed 500-row commit windows; the hub cursor tracks `(file, rowsProcessed)` so re-runs skip already-ingested rows. |
| `50-kotlin-row-hook.yaml` | Kotlin escape hatch: a `spec.mapping.cell` hook (`normalize-date.kts`) rewrites the legacy `DD/MM/YYYY` date to ISO `YYYY-MM-DD` at ingest time. |
| `30-sftp-key.yaml` | Remote pull over SFTP (creds via env var named by `password_env`). |
| `40-s3-prefix.yaml` | Every `.csv` under an S3 / MinIO prefix (creds via `access_key_env` / `secret_key_env`). |
| `45-s3-headerless-archive.yaml` | A headerless, tab-separated export under an S3 prefix (`parser.columns`), archived to another bucket after commit. |
| `60-s3-every-transformer.yaml` | One S3 export run through **every** `spec.mapping` transformer (all but `aggregator`) — the walk-through lives in [transformers.md](../transformers.md#one-manifest-every-transformer). |

> **Remote cursor:** remote connectors (`sftp`, `s3`) track a per-file watermark — each run ingests
> only files newer than the last processed one; replays are idempotent via `_row_hash` dedup.

### Supporting files

| File | Purpose |
|------|---------|
| `data/customers.csv` | Primary sample data (3 columns, `row_id` / `email` / `signup_date`). |
| `data/customers-2.csv` | Second file so the glob in `20-` matches more than one source. |
| `hooks/normalize-date.kts` | Cell hook script referenced by `50-kotlin-row-hook.yaml`. |

---

## Notes

- The sample data uses `row_id` (not `id`) as the primary key column.  A source
  column sharing one of the hub's seven reserved column names (`id`, `_row_hash`,
  `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id`) is refused
  at ingestion time.
- These manifests are the live E2E corpus consumed by the `lm`-binary test suite;
  treat any edit as a code change and verify it does not break the suite.
- Remote transport examples (SFTP, S3) are in `30-sftp-key.yaml` and `40-s3-prefix.yaml`; the
  docker-compose `sftp` service (port 2222) serves the seed data for local E2E runs.
