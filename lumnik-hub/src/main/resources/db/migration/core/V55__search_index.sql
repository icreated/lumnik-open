-- V55__search_index.sql — kind:SearchIndex declarations and their purge obligations (#669).
--
-- A SearchIndex is the allowlist of one kind:Entity's canonical columns that may leave PostgreSQL
-- for an optional search provider. Tenant-bound + RLS, like connector.export_manifest: the entity
-- manifest itself is deployment-global, the decision to index it is a tenant's.

CREATE TABLE core.search_index (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      BIGINT      NOT NULL,
    name           TEXT        NOT NULL,
    entity         TEXT        NOT NULL,
    language       TEXT        NOT NULL,
    manifest_yaml  TEXT        NOT NULL,
    manifest       JSONB       NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'valid',
    invalid_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     BIGINT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     BIGINT,
    UNIQUE (tenant_id, name),
    CONSTRAINT search_index_status_check CHECK (status IN ('valid', 'invalid')),
    CONSTRAINT search_index_reason_check CHECK ((status = 'invalid') = (invalid_reason IS NOT NULL))
);

CREATE INDEX search_index_entity_idx ON core.search_index (lower(entity));

ALTER TABLE core.search_index ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.search_index
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON core.search_index TO lumnik_app;

-- The fail-closed purge contract #701 consumes: a DELETE of a declaration writes one row here in the
-- same transaction, and nothing but #701's consumer (on the admin pool) may ever clear it. The
-- obligation outlives the declaration, so documents already projected cannot survive a delete made
-- while a provider was down, absent, or not yet installed.
CREATE TABLE core.search_index_purge (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL,
    name         TEXT        NOT NULL,
    entity       TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    requested_by BIGINT
);

CREATE INDEX search_index_purge_tenant_idx ON core.search_index_purge (tenant_id, name);

ALTER TABLE core.search_index_purge ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.search_index_purge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- Append-only for the application role, the V47 posture: 01-roles.sh's default privileges grant
-- lumnik_app UPDATE/DELETE on every table lumnik_admin creates, so they are revoked explicitly.
-- Guarded on the role's existence, like V47.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT SELECT, INSERT ON core.search_index_purge TO lumnik_app;
    GRANT USAGE ON SEQUENCE core.search_index_purge_id_seq TO lumnik_app;
    REVOKE UPDATE, DELETE, TRUNCATE ON core.search_index_purge FROM lumnik_app;
  END IF;
END $$;
