-- V13__connector_endpoint.sql — REST connector endpoints + cursor state

CREATE TABLE connector.connector_endpoint (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id    UUID NOT NULL REFERENCES connector.connector(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL,
    org_id          BIGINT NOT NULL DEFAULT 0,
    name            TEXT NOT NULL,
    method          TEXT NOT NULL DEFAULT 'GET',
    path            TEXT NOT NULL,
    config          JSONB NOT NULL,
    schedule        TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    last_run_at     TIMESTAMPTZ,
    last_run_status TEXT,
    archived_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL DEFAULT 0,
    updated_by      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (connector_id, name)
);
CREATE INDEX idx_endpoint_connector ON connector.connector_endpoint (connector_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_endpoint_tenant    ON connector.connector_endpoint (tenant_id)    WHERE deleted_at IS NULL;
ALTER TABLE connector.connector_endpoint ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON connector.connector_endpoint
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE TABLE connector.cursor_state (
    endpoint_id     UUID PRIMARY KEY REFERENCES connector.connector_endpoint(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL,
    mode            TEXT NOT NULL,
    cursor_value    TEXT,
    last_synced_at  TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE connector.cursor_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON connector.cursor_state
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

ALTER TABLE connector.connector_run
    ADD COLUMN endpoint_id UUID NULL REFERENCES connector.connector_endpoint(id) ON DELETE SET NULL;
CREATE INDEX idx_run_endpoint ON connector.connector_run (endpoint_id, started_at DESC) WHERE endpoint_id IS NOT NULL;

ALTER TABLE connector.connector_dead_letter
    ADD COLUMN endpoint_id UUID NULL REFERENCES connector.connector_endpoint(id) ON DELETE CASCADE;

GRANT SELECT, INSERT, UPDATE ON connector.connector_endpoint TO lumnik_app;
GRANT SELECT, INSERT, UPDATE ON connector.cursor_state TO lumnik_app;
