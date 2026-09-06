-- V18: the NULLIF guard, in a history the existing healer runs BEFORE.
--
-- core/V21 wrapped every tenant policy's GUC cast in NULLIF(..., '') so an EMPTY
-- app.current_tenant degrades to "no rows" instead of throwing 22P02 (invalid input
-- syntax for type bigint: ""). core/V31 then generalised it into the schema-agnostic
-- healer copied below: a loop over pg_policies, by SHAPE rather than by name, so it
-- reaches tables nobody could enumerate at migration time.
--
-- V31 heals nothing here, and not because it is scoped to core -- its DO block names
-- no schema at all. It is the ORDER. PlatformFlywayRunner migrates core first
-- (42 migrations, V31 among them), then identity, then rag. When V31's loop ran,
-- pg_policies held no identity rows: V6 and V12, which CREATE these policies with the
-- raw cast, had not run yet. A healer that runs before the thing it heals exists heals
-- nothing, and reports success.
--
-- So each history carries its own copy, placed after its own CREATE POLICYs. The block
-- is idempotent (a healed policy contains NULLIF and stops matching) and name-free, so
-- it also covers any future identity policy written in the old shape -- which is why
-- this is the healer rather than six ALTER statements. identity.session needs no
-- mention: V15 dropped the table, so pg_policies has nothing to offer for it.
--
-- Nothing here changes who can see what. An empty GUC already meant "no tenant", and
-- NULL = tenant_id is false for every row; this only stops the same answer from
-- arriving as an exception. The difference was observable: with the GUC empty,
-- core.notification_rule answered [] while identity.app_user answered HTTP 500.
--
-- Swept by EmptyTenantGucRlsIT, which asks pg_class.relrowsecurity which tables are
-- under RLS instead of trusting a list -- the check that was missing when V21 claimed
-- to have covered "every tenant RLS policy".

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
