-- V31: extend the V21 NULLIF guard to DYNAMICALLY created tables.
--
-- V21 ALTERed every tenant policy that existed at migration time — but dynamic
-- connector tables (connector.t_*, ext.*, mapping-target schemas) are created at
-- RUN time by DynamicTableWriter / HybridJsonbWriter, which kept emitting the
-- unguarded cast:
--   tenant_id = current_setting('app.current_tenant', true)::bigint
-- An empty GUC ('') on those tables still threw 22P02 — the very failure mode
-- V21's motivating incident hit, on the very tables that incident crashed on.
--
-- The writers now emit the guarded predicate at creation; this migration heals
-- every table created before the fix. Surgical by shape, not by name (dynamic
-- table names are unknowable here):
--   * only policies named tenant_isolation,
--   * only the plain unguarded cast (NOT LIKE '%NULLIF%'),
--   * never the system-sentinel policies (V4/V22/V24 accept '0' via OR — skipped).
-- Idempotent: a healed policy contains NULLIF and no longer matches.

DO $$
DECLARE p record;
BEGIN
  FOR p IN
    SELECT schemaname, tablename
    FROM pg_policies
    WHERE policyname = 'tenant_isolation'
      AND qual LIKE '%current_setting(''app.current_tenant''%'
      AND qual NOT LIKE '%NULLIF%'
      AND qual NOT LIKE '% OR %'
  LOOP
    EXECUTE format(
      'ALTER POLICY tenant_isolation ON %I.%I '
      || 'USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::bigint)',
      p.schemaname, p.tablename);
  END LOOP;
END $$;
