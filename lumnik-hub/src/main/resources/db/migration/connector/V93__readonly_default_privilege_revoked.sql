-- V93 — the Ask-to-SQL role reads data only, granted when the hub creates it (#2008).
-- V15 gave lumnik_readonly SELECT on every table of schema connector, present and future. V15 is applied history
-- (Flyway checksums it), so the default it set is taken back here instead. The hub already grants each data relation
-- explicitly at creation: the dynamic tables (DynamicTableWriter, HybridJsonbWriter) and the entity views
-- (EntityViewCompiler); core.entity_identity, which those views join, keeps V50's grant.
-- None of the eleven readonly readers reads the seven platform tables revoked below (audit on #2008); V92 took six
-- others. ReadonlyGrantsInventoryRlsIT fails when a migration makes another one readable.
-- ALTER DEFAULT PRIVILEGES without FOR ROLE acts for the role running this migration. V15 ran as that same role
-- (the Flyway role, lumnik_admin), which is the assumption; the check at the end names any default left under
-- another role (a V15 replayed by hand as a superuser, a renamed Flyway role), or one granted to PUBLIC, with the
-- command that removes it. It runs once: a default re-added later is caught by ReadonlyGrantsInventoryRlsIT only.
-- Guarded like V15 and V92 where the role is absent.

DO $$
DECLARE r record;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    ALTER DEFAULT PRIVILEGES IN SCHEMA connector REVOKE SELECT ON TABLES FROM lumnik_readonly;
    REVOKE SELECT ON connector.app_registration, connector.dynamic_table, connector.entity_manifest,
                     connector.export_manifest, connector.export_run, connector.saved_view, connector.schema_card
      FROM lumnik_readonly;
    FOR r IN SELECT DISTINCT
                    CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE a.grantee::regrole::text END AS grantee,
                    pg_catalog.pg_get_userbyid(d.defaclrole) AS grantor, coalesce(n.nspname, '(all)') AS schema,
                    format('ALTER DEFAULT PRIVILEGES FOR ROLE %I%s REVOKE SELECT ON TABLES FROM %s',
                           pg_catalog.pg_get_userbyid(d.defaclrole),
                           coalesce(' IN SCHEMA ' || quote_ident(n.nspname), ''),
                           CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(a.grantee::regrole::text) END) AS cmd
             FROM pg_catalog.pg_default_acl d
             LEFT JOIN pg_catalog.pg_namespace n ON n.oid = d.defaclnamespace
             CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) a
             WHERE d.defaclobjtype = 'r' AND a.privilege_type = 'SELECT'
               AND (a.grantee = 0 OR a.grantee = 'lumnik_readonly'::regrole)
    LOOP
      RAISE WARNING 'a default privilege still hands % new tables (granted by %, schema %): as a superuser, %',
        r.grantee, r.grantor, r.schema, r.cmd;
    END LOOP;
  ELSE
    RAISE NOTICE 'lumnik_readonly role absent — skipping V93. If it is created later and V15''s grants are replayed, '
      'also run ALTER DEFAULT PRIVILEGES IN SCHEMA connector REVOKE SELECT ON TABLES FROM lumnik_readonly, and revoke '
      'the connector platform tables (docs/security/rls.md#what-the-read-only-role-cannot-call).';
  END IF;
END $$;
