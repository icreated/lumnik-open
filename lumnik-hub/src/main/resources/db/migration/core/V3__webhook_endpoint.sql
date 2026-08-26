-- lumnik Platform — V3 Org Master Registry: Webhook endpoint registry + outbox retry tracking
-- Enables multi-tenant webhook delivery for signed-webhook sync across enterprise sites

-- Outbox retry tracking
ALTER TABLE core.event_outbox
  ADD COLUMN attempts   INT NOT NULL DEFAULT 0,
  ADD COLUMN last_error TEXT;

CREATE INDEX idx_event_outbox_retry ON core.event_outbox (id)
  WHERE published_at IS NULL AND attempts < 3;

-- Webhook endpoint registry
CREATE SEQUENCE core.webhook_endpoint_id_seq START 1000;

CREATE TABLE core.webhook_endpoint (
    id           BIGINT      NOT NULL DEFAULT nextval('core.webhook_endpoint_id_seq'),
    tenant_id    BIGINT      NOT NULL,
    org_id       BIGINT      NOT NULL DEFAULT 0,
    name         VARCHAR(120) NOT NULL,
    target_url   VARCHAR(500) NOT NULL,
    secret       VARCHAR(255) NOT NULL,
    event_types  TEXT[]       NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    archived_at  TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_webhook_endpoint PRIMARY KEY (id),
    CONSTRAINT uq_webhook_endpoint_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_webhook_endpoint_tenant_event
    ON core.webhook_endpoint (tenant_id) WHERE enabled = TRUE AND deleted_at IS NULL;

ALTER TABLE core.webhook_endpoint ENABLE ROW LEVEL SECURITY;

CREATE POLICY webhook_endpoint_tenant_isolation ON core.webhook_endpoint
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

COMMENT ON TABLE core.webhook_endpoint IS 'Registry of webhook receivers; supports multi-site sync via HMAC-signed payloads.';
COMMENT ON COLUMN core.webhook_endpoint.org_id IS 'Optional: if 0, endpoint is tenant-level (receives all org events); if > 0, scoped to single org.';
COMMENT ON COLUMN core.webhook_endpoint.secret IS 'HMAC signing key; never returned in REST API responses.';
COMMENT ON COLUMN core.webhook_endpoint.event_types IS 'Array of subscribed event types (e.g. {org.created, org.updated}).';

COMMENT ON TABLE core.event_outbox IS 'Transactional outbox for domain events; dispatched to webhook_endpoint subscribers with retry on failure.';
COMMENT ON COLUMN core.event_outbox.attempts IS 'Retry counter; resets on successful publish.';
COMMENT ON COLUMN core.event_outbox.last_error IS 'Human-readable error message from most recent delivery attempt.';
