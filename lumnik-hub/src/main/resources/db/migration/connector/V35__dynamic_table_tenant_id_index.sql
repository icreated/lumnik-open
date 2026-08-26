-- V35: backfill a tenant_id-leading index on every existing connector.t_* dynamic table.
--
-- DynamicTableWriter.createTable emits tenant_id as a plain column, and RlsDdl.tenantIsolationPolicy
-- filters every RLS-enforced read on it — but nothing indexed it. Every view render, dormancy sweep
-- and ask-to-SQL query against ingested data was sequentially scanning the tenant's largest tables.
-- DynamicTableWriter.enableRLS now emits the index (RlsDdl.tenantIdIndex) for every table it creates
-- from here on; this migration heals tables created before that fix.
--
-- Surgical by shape, not by name (dynamic table names are unknowable here): iterates connector.t_*
-- via pg_tables rather than hardcoding names. CREATE INDEX, not CONCURRENTLY — Flyway runs migrations
-- inside a transaction, and CONCURRENTLY cannot run in one. HybridJsonbWriter's ext.* tables already
-- carry a tenant_id-leading index from their UNIQUE (tenant_id, external_id) constraint — out of
-- scope here.

DO $$
DECLARE t record;
BEGIN
  FOR t IN
    SELECT schemaname, tablename
    FROM pg_tables
    WHERE schemaname = 'connector'
      AND tablename LIKE 't\_%' ESCAPE '\'
  LOOP
    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS %I ON %I.%I (tenant_id)',
      t.tablename || '_idx_tenant_id', t.schemaname, t.tablename);
  END LOOP;
END $$;
