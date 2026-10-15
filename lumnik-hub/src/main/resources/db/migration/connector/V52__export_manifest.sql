-- V52__export_manifest.sql — kind:Export manifest schema (tenant-bound, RLS-enforced)
CREATE TABLE connector.export_manifest (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        BIGINT      NOT NULL,
    name             TEXT        NOT NULL,
    scope            TEXT        NOT NULL,
    entity_name      TEXT        NOT NULL,
    destination_kind TEXT        NOT NULL,
    schedule         TEXT,
    yaml_text        TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

ALTER TABLE connector.export_manifest ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON connector.export_manifest
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON connector.export_manifest TO lumnik_app;
