# Live piscine proof — unified endpoint engine (Phase 1, Lot 5a)

**Date:** 2026-06-01 · **RLS:** disabled on the hub (workaround, to isolate the ingest fix)

Proves the unified endpoint-driven engine + the runaway fix on the real Adempiere DB,
end-to-end through the **declarative manifest** path.

## What was run
1. Platform started against the real hub (`%dev` → `localhost:5433/lumnik`), with
   `WOW_SRC_PASSWORD` in its environment. Flyway applied V17/V18; the startup
   `FlatConnectorEndpointMigration` migrated the 4 legacy flat connectors on the hub to
   synthetic endpoints.
2. `lm apply -f scripts/wow/piscine-jdbc.yaml` → `{connectorId, endpointsApplied:1}`.
   (Declarative path: `connection.password_env` + inferred postgres dialect, schema
   `adempiere`, mode `full`, pk `c_order_id`.)
3. `POST /api/platform/connectors/<id>/run` → fan-out → the `c_order` endpoint.

## Result — RUNAWAY FIX PROVEN
```
connector_run: status=Completed  records_in=291157  records_out=291157  chunks=583  endpoint_id=54a02c18…
source:        adempiere.c_order count = 291157
```
- `records_in == 291157` = **exactly the source count** — one clean full-scan pass
  (291157 / 500 = 583 chunks). Pre-fix this ballooned into the millions and never
  terminated. **No runaway.**
- Run is endpoint-keyed (`endpoint_id` set); the four hardenings (advisory lock, fresh
  cursor read, `@Version`, cron) operate per endpoint.

## Known gap (NOT the runaway fix) — writer not wired on the apply path
`connector.t_c_order` was **not created**: a bare `jdbc-generic` connector has no
`writer()` (SPI default null), so `ChunkExecutor` counts reads (`AllWritten`) but does
not persist. `records_out=291157` is therefore a count, not rows written. Wiring a
DynamicTableWriter/mapping into the apply path (so data lands in `t_*` and the WOW acts
can query it) is a separate task — candidate for Phase 2 / a mapping-wiring lot.

## Still pending
- Re-enable RLS on the hub (`ENABLE ROW LEVEL SECURITY` on the 10 `connector.*` tables)
  and re-verify — deferred per "prove the fix first".
