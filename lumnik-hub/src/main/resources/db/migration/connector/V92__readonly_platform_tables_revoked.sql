-- V92 — the Ask-to-SQL role no longer reads the connector platform tables (#1987).
-- V15 granted lumnik_readonly SELECT on every table of the connector schema. Ask needs the dynamic tables
-- and entity views; none of the hub's own readonly readers (Ask, views, processes, exports, workflows,
-- the search projection) reads these six: connector configs, encrypted credentials, rejected raw rows,
-- endpoints, runs and cursors. A query that escaped the guards could still read them inside its tenant.
-- Guarded like V15 where the role is absent. The hub checks it before each Ask (ReadonlyHardening).

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    REVOKE SELECT ON connector.connector, connector.connector_credential, connector.connector_dead_letter,
                     connector.connector_endpoint, connector.connector_run, connector.connector_cursor
      FROM lumnik_readonly;
  ELSE
    RAISE NOTICE 'lumnik_readonly role absent — skipping V92. If it is created later and V15''s grants are replayed, '
      'revoke these six tables by hand (docs/security/rls.md#what-the-read-only-role-cannot-call).';
  END IF;
END $$;
