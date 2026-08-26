-- V14__platform_secret.sql — Encrypted secret store (pgcrypto)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.secret (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        BIGINT      NOT NULL,
    org_id           BIGINT      NOT NULL DEFAULT 0,
    name             TEXT        NOT NULL,
    value_encrypted  BYTEA       NOT NULL,
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       BIGINT      NOT NULL DEFAULT 0,
    archived_at      TIMESTAMPTZ,
    rotated_at       TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_secret_tenant ON platform.secret (tenant_id)       WHERE deleted_at IS NULL;
CREATE INDEX idx_secret_name   ON platform.secret (tenant_id, name) WHERE deleted_at IS NULL;

ALTER TABLE platform.secret ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform.secret
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.secret TO lumnik_app;
