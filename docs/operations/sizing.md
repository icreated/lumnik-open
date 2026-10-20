# Sizing & load tests

## Storage — the sizing question this page does NOT answer

The load tests below measure throughput and latency, not disk. What grows, and what is purged:

| Data | Purged? | When | Window | Setting |
|---|---|---|---|---|
| Ingested tables (`connector.t_*`), RAG corpus (`rag.chunk`), run history | never ([no retention policy](../security/gdpr.md#where-personal-data-can-live)) | when the connector is deleted, or by hand | — | — |
| Event outbox | yes, never past the slowest consumer | daily 04:30 | 30 days | `LUMNIK_EVENTS_RETENTION_DAYS` |
| Audit trail | yes | daily 04:45 | 365 days (`0` disables) | `LUMNIK_AUDIT_RETENTION_DAYS` |
| Webhook delivery journal (payloads and receiver responses) | yes | daily 05:00 | 90 days | `LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS` |

No disk metric ships ([observability](observability.md)): supervise the PostgreSQL disk yourself.
Check the database size from the repo root:

```
$ docker compose -f docker-compose.selfhost.yml exec postgres psql -U lumnik -d lumnik \
    -c "SELECT pg_size_pretty(pg_database_size('lumnik'))"
 pg_size_pretty
----------------
 11 MB
(1 row)
```

- **No growth multiplier is measured.** The mirror is roughly your declared source tables plus
  system columns; `rag.chunk` adds the text and a 768-float vector per chunk.
- **[Backups](backup-restore.md) grow with the mirror**; `backup.sh --keep N` bounds how many
  dumps you keep.

## Throughput & latency — the measured part

--8<-- "loadtest/README.md"

---

The scenarios live in [`loadtest/`](https://github.com/icreated/lumnik-open/tree/main/loadtest);
deploy first ([self-host](../deploy.md)), then size.
