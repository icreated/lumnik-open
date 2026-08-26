-- lumnik Platform — V5 Admin Console foundation
-- Adds: pgcrypto, integration platform/auth_mode/encrypted secret, webhook_delivery history.

-- Install pgcrypto into public schema so its functions are always visible
-- regardless of which schema is first in search_path.
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;

-- Integration columns on existing webhook_endpoint
ALTER TABLE core.webhook_endpoint
    ADD COLUMN platform         VARCHAR(32)  NOT NULL DEFAULT 'generic',
    ADD COLUMN auth_mode        VARCHAR(16)  NOT NULL DEFAULT 'hmac',
    ADD COLUMN secret_encrypted BYTEA;

-- Pre-MVP: no production rows to migrate. Drop the plaintext column.
ALTER TABLE core.webhook_endpoint ALTER COLUMN secret DROP NOT NULL;
ALTER TABLE core.webhook_endpoint DROP COLUMN secret;

COMMENT ON COLUMN core.webhook_endpoint.platform IS
    'Integration platform tile: make | zapier | n8n | generic.';
COMMENT ON COLUMN core.webhook_endpoint.auth_mode IS
    'How the platform authenticates incoming POSTs: hmac | bearer | none.';
COMMENT ON COLUMN core.webhook_endpoint.secret_encrypted IS
    'pgp_sym_encrypt of the signing secret (HMAC) or bearer token. Key from lumnik.crypto.secret-key.';

-- Delivery history
CREATE SEQUENCE core.webhook_delivery_id_seq START 1000;

CREATE TABLE core.webhook_delivery (
    id            BIGINT       NOT NULL DEFAULT nextval('core.webhook_delivery_id_seq'),
    tenant_id     BIGINT       NOT NULL,
    endpoint_id   BIGINT       NOT NULL REFERENCES core.webhook_endpoint(id),
    event_type    VARCHAR(120) NOT NULL,
    payload       JSONB        NOT NULL,
    http_status   INT,
    response_body TEXT,
    error         TEXT,
    duration_ms   INT,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_webhook_delivery PRIMARY KEY (id)
);

CREATE INDEX ix_webhook_delivery_endpoint
    ON core.webhook_delivery (endpoint_id, attempted_at DESC);
CREATE INDEX ix_webhook_delivery_failed
    ON core.webhook_delivery (tenant_id, attempted_at DESC)
    WHERE http_status IS NULL OR http_status >= 400;

ALTER TABLE core.webhook_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY webhook_delivery_tenant_isolation ON core.webhook_delivery
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

COMMENT ON TABLE core.webhook_delivery IS
    'Per-attempt webhook delivery history; one row per HTTP call. Used by admin UI for inspection + replay.';
