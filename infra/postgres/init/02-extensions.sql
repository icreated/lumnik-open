-- Make a fresh DB ready for the hub, on first init (as the bootstrap superuser).
-- Runs after 01-roles.sql.

-- PlatformFlywayRunner runs migrations as lumnik_admin, which must CREATE SCHEMA;
-- the rag schema's V1 needs the pgvector extension (superuser-only to create).
GRANT CREATE, CONNECT, TEMP ON DATABASE lumnik TO lumnik_admin;
CREATE EXTENSION IF NOT EXISTS vector;

-- Pre-create the app schemas owned by the migration role so Flyway's
-- CREATE SCHEMA IF NOT EXISTS is a no-op, then grant the non-owner runtime roles
-- USAGE. Schema USAGE is NOT covered by ALTER DEFAULT PRIVILEGES (01-roles.sql),
-- so without this the app role hits 'permission denied for schema core' at runtime.
CREATE SCHEMA IF NOT EXISTS core      AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS identity  AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS connector AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS platform  AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS rag       AUTHORIZATION lumnik_admin;
GRANT USAGE ON SCHEMA core, identity, connector, platform, rag TO lumnik_app, lumnik_readonly;

-- The Ask-to-SQL boundary (#1987). lumnik_readonly runs SQL a model wrote; RLS binds it to one tenant
-- through app.current_tenant, and any role may overwrite a custom setting. Taken from PUBLIC, given back
-- to the hub's own roles, so lumnik_readonly loses them — and so does any other role that is not a superuser:
--   set_config            rewrites app.current_tenant inside the query, or the pooled session's settings;
--   *_to_xml*, ts_stat, ts_rewrite  run SQL held in a string literal, which no guard reads;
--   pg_*advisory*         a session lock outlives the query on the pooled connection;
--   pg_stat_activity, pg_stat_get_activity/backend_*  every Ask runs as one role, so they show other
--                         tenants' SQL in flight (given back to pg_monitor and pg_read_all_stats);
--   pg_cancel/terminate_backend  the same role may signal every other Ask (given back to pg_signal_backend);
--   pg_sleep*, pg_notify  a wait that holds a pooled connection, a message to every listening session (#2090);
--   lo_*, loread          a large object, read whatever the tenant (#2090).
-- A grant made to lumnik_readonly itself is revoked too. A right it inherits from a role it is a member of is
-- not: the hub's check sees it, and the member must be removed by hand (docs/security/rls.md).
-- The command SET LOCAL (TenantRlsScope) does not go through set_config and keeps working.
-- Only a superuser may do this (the functions are pg_catalog's), hence here and not in Flyway.
-- The hub checks it before each Ask (ReadonlyHardening) and keeps Ask closed until it holds; it repeats the
-- predicate below word for word (ReadonlyHardeningInventoryTest).
-- Idempotent: selfhost up.sh and the helm bootstrap Job (LUMNIK_PG_MODE=external) replay it on every run; the
-- embedded helm database and the dev docker-compose.yml run it only when the volume is created (docs/security/rls.md).
-- A backup role that is not a superuser loses set_config too, which pg_dump calls: grant it back to that role.
DO $$
DECLARE f regprocedure;
BEGIN
  FOR f IN
    SELECT p.oid::regprocedure FROM pg_catalog.pg_proc p
    WHERE p.pronamespace = 'pg_catalog'::regnamespace
      AND (p.proname IN ('set_config', 'ts_stat', 'ts_rewrite', 'pg_cancel_backend', 'pg_terminate_backend',
                         'pg_notify', 'loread')
           OR p.proname ~ '^(pg_sleep|lo_)'
           OR p.proname ~ '^(query|table|cursor|schema|database)_to_xml'
           OR p.proname ~ '^pg_(try_)?advisory_'
           OR p.proname ~ '^pg_stat_get_(activity|backend_)')
  LOOP
    EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC, lumnik_readonly', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO lumnik_app, lumnik_admin', f);
    IF f::text ~ '^pg_stat_get_' THEN
      EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pg_monitor, pg_read_all_stats', f);
    ELSIF f::text ~ '^pg_(cancel|terminate)_backend' THEN
      EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pg_signal_backend', f);
    END IF;
  END LOOP;
END $$;
REVOKE SELECT ON pg_catalog.pg_stat_activity FROM PUBLIC, lumnik_readonly;
GRANT SELECT ON pg_catalog.pg_stat_activity TO lumnik_app, lumnik_admin, pg_monitor, pg_read_all_stats;
-- A role that does not own pg_catalog (a managed database's "superuser") gets a WARNING, not an error, from the
-- REVOKEs above: fail here instead of reporting success while nothing changed. The ACL itself is read (PUBLIC, or
-- lumnik_readonly by name), so a right inherited through a membership is not blamed on ownership; the hub reports it.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
             LEFT JOIN LATERAL pg_catalog.aclexplode(p.proacl) a ON true
             WHERE p.oid = 'pg_catalog.set_config(text,text,boolean)'::regprocedure
               AND (p.proacl IS NULL
                    OR a.privilege_type = 'EXECUTE' AND a.grantee IN (0, 'lumnik_readonly'::regrole))) THEN
    RAISE EXCEPTION 'lumnik_readonly can still call set_config: run this file as the role that owns pg_catalog '
      '(docs/security/rls.md#what-the-read-only-role-cannot-call)';
  END IF;
END $$;
