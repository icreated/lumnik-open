-- V24 (É2.a): events spine — immutable envelope + per-consumer cursors.
-- Study: docs/studies/2026-06-12-e2-events-study.md §4-§5.
-- The event row becomes append-only; all mutable delivery state lives with consumers
-- (core.event_consumer cursors, core.webhook_delivery attempts).

-- 1. Per-consumer cursors (system-scope: positions span tenants — no RLS, like flyway history)
CREATE TABLE core.event_consumer (
    consumer_name VARCHAR(80)  PRIMARY KEY,
    last_event_id BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE core.event_consumer IS
    'One cursor per outbox consumer (webhook, email, ...). Rewind = replay.';

-- 2. Cutover seed: a migrated DB must not replay any historical event to webhooks.
--    A cursor is a single watermark and legacy published/pending rows interleave by id,
--    so selective "deliver only the pending tail" is impossible (that needed the per-row
--    published_at we are dropping). Pin past EVERY existing row: already-delivered events
--    must not re-fire (endpoints aren't guaranteed idempotent); the legacy pending tail is
--    not auto-replayed — rewind the cursor to redeliver it if ever needed. Only 'webhook' is
--    seeded: email's notification_rule table is new/empty, so replaying history sends nothing.
--    On a fresh DB this seeds webhook@0 (MAX over no rows), which the sweeper would create anyway.
INSERT INTO core.event_consumer (consumer_name, last_event_id)
SELECT 'webhook', COALESCE(MAX(id), 0) FROM core.event_outbox;

-- 3. Envelope: consumer state OUT (drops cascade the V1/V3 partial indexes on these columns)
ALTER TABLE core.event_outbox
    DROP COLUMN published_at,
    DROP COLUMN attempts,
    DROP COLUMN last_error;

-- 4. Envelope: identity/scope/time IN
ALTER TABLE core.event_outbox
    ADD COLUMN org_id      BIGINT,
    ADD COLUMN source      VARCHAR(120) NOT NULL DEFAULT 'system',
    ADD COLUMN entity      VARCHAR(120),
    ADD COLUMN entity_key  VARCHAR(200),
    ADD COLUMN occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW();

-- Backfill: pre-spine rows have no recorded occurrence time; created_at is the best
-- approximation. Without this the DEFAULT NOW() above stamps every historical row with
-- the migration timestamp (occurred_at vs created_at semantics lost). No-op on a fresh DB.
-- New rows get occurred_at from the application (EventOutboxRow), the DEFAULT is a safety net.
UPDATE core.event_outbox SET occurred_at = created_at;

-- 5. Dedup: tenant-scoped, partial (NULL = internal event, no dedup semantics)
ALTER TABLE core.event_outbox DROP CONSTRAINT IF EXISTS event_outbox_idempotency_key_key;
ALTER TABLE core.event_outbox RENAME COLUMN idempotency_key TO dedup_key;
ALTER TABLE core.event_outbox ALTER COLUMN dedup_key TYPE VARCHAR(200);
CREATE UNIQUE INDEX uq_event_outbox_dedup ON core.event_outbox (tenant_id, dedup_key)
    WHERE dedup_key IS NOT NULL;

COMMENT ON TABLE core.event_outbox IS
    'Immutable append-only event log; consumers track their own position in core.event_consumer.';

-- 6. Webhook delivery gains per-delivery retry state (the state that left the envelope)
ALTER TABLE core.webhook_delivery
    ADD COLUMN event_id  BIGINT,
    ADD COLUMN attempts  INT     NOT NULL DEFAULT 1,
    ADD COLUMN succeeded BOOLEAN NOT NULL DEFAULT FALSE;
-- Backfill: historical deliveries must not enter the retry window.
UPDATE core.webhook_delivery SET succeeded = TRUE  WHERE http_status BETWEEN 200 AND 299;
UPDATE core.webhook_delivery SET attempts  = 3     WHERE http_status IS NULL OR http_status NOT BETWEEN 200 AND 299;
CREATE INDEX idx_webhook_delivery_retry ON core.webhook_delivery (id)
    WHERE succeeded = FALSE AND attempts < 3;

-- The retry sweep runs in system mode: give webhook_delivery the same '0' sentinel
-- branch event_outbox has had since V4 (cast guarded as in V21).
ALTER POLICY webhook_delivery_tenant_isolation ON core.webhook_delivery
    USING (
        current_setting('app.current_tenant', true) = '0'
        OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint
    );

-- 7. Subscription refinement (consulted from É2.b; column ships now)
ALTER TABLE core.webhook_endpoint ADD COLUMN entity_filter VARCHAR(120);
COMMENT ON COLUMN core.webhook_endpoint.entity_filter IS
    'Optional: restrict data.row.changed to a single hub table (É2.b gate).';

-- 8. Email notification rules (replaces the hardcoded org.created ops email)
CREATE SEQUENCE core.notification_rule_id_seq START 1000;
CREATE TABLE core.notification_rule (
    id            BIGINT       NOT NULL DEFAULT nextval('core.notification_rule_id_seq'),
    tenant_id     BIGINT       NOT NULL,
    event_pattern VARCHAR(120) NOT NULL,
    recipient     VARCHAR(320) NOT NULL,
    template      VARCHAR(120) NOT NULL DEFAULT 'event-notification',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_rule PRIMARY KEY (id)
);
ALTER TABLE core.notification_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_rule_tenant_isolation ON core.notification_rule
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON COLUMN core.notification_rule.event_pattern IS
    'Exact type or prefix pattern ending in .* (e.g. connector.run.*), same matching as webhook_endpoint.event_types.';
