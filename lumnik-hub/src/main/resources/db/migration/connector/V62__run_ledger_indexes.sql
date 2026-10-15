-- V62__run_ledger_indexes.sql — the run history is read and deleted through indexes (#892).
--
-- connector_run and connector_dead_letter are never purged, and three paths over them had no index:
--
-- 1. The runs ledger (GET /api/platform/runs, which `lm`'s :runs asks on every run event) reads a
--    tenant's newest runs: WHERE tenant_id = ? ORDER BY started_at DESC LIMIT n — the tenant predicate
--    is the query's own (ConnectorRunRepository.findRecent), since the RLS policy is an OR on a setting
--    that no index can serve. Every existing index on connector_run leads with another column or is
--    partial, so each page scanned and sorted the whole history. Leading with tenant_id and carrying
--    started_at DESC lets the planner walk the index and stop at the LIMIT.
--
-- 2. The same page counts each run's real-loss dead letters. Nothing indexed run_id, so each count
--    scanned the dead-letter table — every row carrying its source record as JSONB.
--
-- 3. Deleting a connector cascades to its runs (V9), and every deleted run fires the referential action
--    of each foreign key naming it: dead_letter.run_id and dead_letter.resolved_run_id (SET NULL, V9)
--    and resolution_run_coverage.run_id (CASCADE, V60, whose primary key leads with tenant_id). Unindexed,
--    that is R runs × a scan of each referencing table, in the delete's one transaction.
--    resolved_run_id is set only on a replayed dead letter, so its index is partial: an equality on the
--    column implies IS NOT NULL, so the referential lookup can still use it.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one (V36).

CREATE INDEX idx_run_tenant_started ON connector.connector_run (tenant_id, started_at DESC);

CREATE INDEX idx_dlq_run ON connector.connector_dead_letter (run_id);

CREATE INDEX idx_dlq_resolved_run ON connector.connector_dead_letter (resolved_run_id)
    WHERE resolved_run_id IS NOT NULL;

CREATE INDEX idx_resolution_run_coverage_run ON core.resolution_run_coverage (run_id);
