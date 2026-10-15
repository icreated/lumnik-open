# Sizing & load tests

## Storage — the sizing question this page does NOT answer

The harness below measures **throughput and latency**, not disk. The disk story, in
three facts:

- **Nothing purges the ingested data.** The source mirror (`connector.t_*`), the RAG corpus
  (`rag.chunk`) and the run history grow until a connector is deleted (which purges its
  tables) or you clean up manually — [no retention policy exists](../security/gdpr.md#where-personal-data-can-live).
- **Three automatic purges** bound the rest: the event outbox (daily at 04:30, 30-day window,
  `lumnik.events.retention-days`, never past the slowest consumer), the audit trail (04:45, 365 days,
  `LUMNIK_AUDIT_RETENTION_DAYS`) and the webhook delivery journal (05:00, 90 days,
  `LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS`) — payloads and receiver responses included.
- **No disk gauge ships**: `/q/metrics` carries JVM/HTTP/pool, not volume usage
  ([observability](observability.md)) — PostgreSQL disk supervision is yours, sized from
  your real source volumes. No measured growth multiplier exists yet (mirror ≈ the
  declared source tables plus system columns; `rag.chunk` adds text + a 768-float vector
  per chunk) — saying so beats a made-up number. [Backups](backup-restore.md) grow with the mirror; the
  script's `--keep N` bounds how many dumps you retain.

## Throughput & latency — the measured part

--8<-- "loadtest/README.md"

---

This page transcludes the real `loadtest/README.md` from the repository — like every
operational page on this site, it cannot drift from what actually runs. The scenarios
themselves live in [`loadtest/`](https://github.com/icreated/lumnik-open/tree/main/loadtest);
deploy first ([self-host](../deploy.md)), then size.
