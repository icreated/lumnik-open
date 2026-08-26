-- V4: Allow the outbox dispatcher to pick all pending rows in system mode.
-- When app.current_tenant = '0' (sentinel set by WebhookOutboxDispatcher.drainBatch),
-- the policy returns TRUE so all rows are visible.
-- In production, lumnik_admin has BYPASSRLS and this policy is never evaluated.

DROP POLICY IF EXISTS tenant_isolation ON core.event_outbox;

CREATE POLICY tenant_isolation ON core.event_outbox
    USING (
        current_setting('app.current_tenant', true) = '0'
        OR tenant_id = current_setting('app.current_tenant', true)::bigint
    );
