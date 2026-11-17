-- V94 (#2011): a hook override carries no code.
--
-- A tenant's lm_admin could store Kotlin in core.hook_tenant_override.script_content
-- (POST /api/platform/hooks/overrides), and the hub compiled and ran it in its own JVM on the
-- tenant's lifecycle events: the hub's environment, its disk and every tenant's data were in
-- reach. The door now refuses a script and the executor runs only the operator's scripts, so the
-- stored ones are cleared here. The toggles (enabled) stay as they are.
--
-- The column stays, and the database now refuses anything in it: during a rolling upgrade a node
-- still on the old code would otherwise store a script again after this migration, and run it
-- (its write now fails there, as a server error: the old code has no word for the refusal). The
-- table is locked against writes first, so no script lands between the clearing and the check.
--
-- To keep a script, export it BEFORE upgrading (docs/operations/upgrade.md gives the query). A
-- lifecycle hook's code is its module's: declared in the module's manifest and built into the hub.
-- The operator's hooks directory serves connector mapping scripts only.
LOCK TABLE core.hook_tenant_override IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
  cleared bigint;
BEGIN
  -- A count only: the identifiers were written through the old door, unbounded in number and in content, and a
  -- migration copies no tenant's values into the log. The upgrade note's export query names them, before upgrading.
  UPDATE core.hook_tenant_override SET script_content = NULL WHERE script_content IS NOT NULL;
  GET DIAGNOSTICS cleared = ROW_COUNT;
  IF cleared > 0 THEN
    RAISE NOTICE 'V94: % tenant hook override script(s) cleared', cleared;
  END IF;
END $$;

ALTER TABLE core.hook_tenant_override
  ADD CONSTRAINT hook_tenant_override_carries_no_script CHECK (script_content IS NULL);
