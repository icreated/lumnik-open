-- V9__connector_core.sql — Connector Framework foundation

CREATE SCHEMA IF NOT EXISTS connector;

-- 1. Connector instance (per tenant)
CREATE TABLE connector.connector (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    name            VARCHAR(120) NOT NULL,
    type_id         VARCHAR(120) NOT NULL,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    credential_id   UUID,  -- soft ref; no FK to allow independent lifecycle
    mapping_id      UUID,  -- soft ref; no FK to allow independent lifecycle
    schedule        VARCHAR(60),
    sync_mode       VARCHAR(20) NOT NULL DEFAULT 'Incremental',
    retry_policy    JSONB NOT NULL DEFAULT '{"maxAttempts":3,"initialDelayMs":1000,"maxDelayMs":30000,"backoffMultiplier":2.0}'::jsonb,
    skip_policy     JSONB NOT NULL DEFAULT '{"type":"SkipUpTo","maxSkips":100}'::jsonb,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_connector_type     ON connector.connector(tenant_id, type_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_connector_schedule ON connector.connector(schedule) WHERE deleted_at IS NULL AND enabled = true AND schedule IS NOT NULL;

-- 2. Cursor (1-1 with connector)
CREATE TABLE connector.connector_cursor (
    connector_id    UUID PRIMARY KEY REFERENCES connector.connector(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    cursor_value    JSONB NOT NULL DEFAULT 'null'::jsonb,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0
);

-- 3. Run history
CREATE TABLE connector.connector_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    connector_id    UUID NOT NULL REFERENCES connector.connector(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL,
    sync_mode       VARCHAR(20) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    records_in      BIGINT NOT NULL DEFAULT 0,
    records_out     BIGINT NOT NULL DEFAULT 0,
    records_skipped BIGINT NOT NULL DEFAULT 0,
    chunk_count     INTEGER NOT NULL DEFAULT 0,
    cursor_before   JSONB,
    cursor_after    JSONB,
    error_message   TEXT,
    error_stack     TEXT,
    triggered_by    VARCHAR(20) NOT NULL,
    triggered_meta  JSONB,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_run_connector_started ON connector.connector_run(connector_id, started_at DESC);
CREATE INDEX idx_run_open              ON connector.connector_run(tenant_id) WHERE status = 'Running';

-- 4. Mapping definitions
CREATE TABLE connector.connector_mapping (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    name            VARCHAR(120) NOT NULL,
    target_entity   VARCHAR(240) NOT NULL,
    spec_format     VARCHAR(20) NOT NULL,
    spec_content    TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, name)
);

-- 5. Credentials (AES-GCM via existing SecretCipher)
CREATE TABLE connector.connector_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(20) NOT NULL,
    ciphertext      BYTEA NOT NULL,
    iv              BYTEA NOT NULL,
    metadata        JSONB,
    rotation_due_at TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, name)
);

-- 6. Dead letter queue
CREATE TABLE connector.connector_dead_letter (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    connector_id    UUID NOT NULL REFERENCES connector.connector(id) ON DELETE CASCADE,
    run_id          UUID REFERENCES connector.connector_run(id) ON DELETE SET NULL,
    payload         JSONB NOT NULL,
    skip_reason     VARCHAR(40) NOT NULL,
    skip_detail     JSONB,
    error_message   TEXT,
    attempts        INTEGER NOT NULL DEFAULT 1,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolution      VARCHAR(20),
    resolved_at     TIMESTAMPTZ,
    resolved_by     BIGINT,
    resolved_run_id UUID REFERENCES connector.connector_run(id) ON DELETE SET NULL,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dlq_pending ON connector.connector_dead_letter(connector_id, captured_at DESC) WHERE resolution IS NULL;

-- RLS on all 6 tables
ALTER TABLE connector.connector             ENABLE ROW LEVEL SECURITY;
ALTER TABLE connector.connector_cursor      ENABLE ROW LEVEL SECURITY;
ALTER TABLE connector.connector_run         ENABLE ROW LEVEL SECURITY;
ALTER TABLE connector.connector_mapping     ENABLE ROW LEVEL SECURITY;
ALTER TABLE connector.connector_credential  ENABLE ROW LEVEL SECURITY;
ALTER TABLE connector.connector_dead_letter ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON connector.connector
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
CREATE POLICY tenant_isolation ON connector.connector_cursor
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
CREATE POLICY tenant_isolation ON connector.connector_run
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
CREATE POLICY tenant_isolation ON connector.connector_mapping
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
CREATE POLICY tenant_isolation ON connector.connector_credential
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
CREATE POLICY tenant_isolation ON connector.connector_dead_letter
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

GRANT USAGE ON SCHEMA connector TO lumnik_app;
