-- V15 — SchemaCard persistence + read-only role for the Ask-to-SQL engine.
-- (V14 is taken by platform/V14__platform_secret.sql; core/connector/platform
--  share one flyway_schema_history, so the next free version is V15.)

CREATE TABLE IF NOT EXISTS connector.schema_card (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   BIGINT NOT NULL,
    scope       TEXT   NOT NULL,
    source_type TEXT   NOT NULL,
    card        JSONB  NOT NULL,
    discovered  BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_schema_card_tenant_scope') THEN
    ALTER TABLE connector.schema_card
      ADD CONSTRAINT uq_schema_card_tenant_scope UNIQUE (tenant_id, scope);
  END IF;
END $$;

ALTER TABLE connector.schema_card ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname = 'schema_card_tenant_isolation') THEN
    CREATE POLICY schema_card_tenant_isolation ON connector.schema_card
      USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
  END IF;
END $$;

GRANT SELECT, INSERT, UPDATE ON connector.schema_card TO lumnik_app;

-- Read-only role grants (SqlGuard layer 1, the physical firewall, spec section 4).
-- The role itself is provisioned OUTSIDE Flyway (infra/postgres/init/01-roles.sql,
-- or by a DBA) because the migration user (lumnik_admin) intentionally lacks
-- CREATEROLE. These GRANTs only touch objects admin owns, so they run under admin.
-- Guarded so the migration still succeeds in an environment where the role is absent
-- (the ask-to-SQL readonly datasource simply won't connect there).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    GRANT USAGE ON SCHEMA connector TO lumnik_readonly;
    GRANT SELECT ON ALL TABLES IN SCHEMA connector TO lumnik_readonly;
    ALTER DEFAULT PRIVILEGES IN SCHEMA connector GRANT SELECT ON TABLES TO lumnik_readonly;
  ELSE
    RAISE NOTICE 'lumnik_readonly role absent — skipping readonly grants. Create it via infra/postgres/init/01-roles.sql (superuser), then re-run.';
  END IF;
END $$;
