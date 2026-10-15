-- V64__webhook_delivery_read_path_indexes.sql — the two delivery pages read through an index (#902).
--
-- core.webhook_delivery is never purged (one row per event × endpoint, plus every replay), and two of
-- its reads had no index serving them:
--
-- 1. The outputs page (GET /api/platform/deliveries — `lm outputs`, the TUI :outputs screen) reads a
--    tenant's deliveries failures first, then newest: WHERE tenant_id = ? ORDER BY succeeded,
--    attempted_at DESC LIMIT n (WebhookDeliveryRepository.recentForTenant). No index led with
--    tenant_id and carried that order, so every page scanned the whole table and sorted the tenant's
--    history. This index holds the page's exact order: the planner walks it and stops at the LIMIT.
--
-- 2. An integration's failed view (GET .../integrations/{id}/deliveries?status=failed) reads WHERE
--    endpoint_id = ? AND succeeded = false ORDER BY attempted_at DESC LIMIT n
--    (recentFailedForEndpoint). ix_webhook_delivery_endpoint has the order but every success too, so
--    the scan read and discarded successes until it had n failures — for a healthy endpoint, its whole
--    history to return nothing. A partial index on the failures holds only what the view returns. It is
--    not V24's retry index (id) WHERE succeeded = FALSE AND attempts < 3, whose rows leave once a
--    delivery caps.
--
-- ix_webhook_delivery_failed (V5) is dropped: its predicate (http_status IS NULL OR http_status >= 400)
-- dates from before V24 made succeeded authoritative — a 3xx is a failure it does not hold — and no read
-- in WebhookDeliveryRepository, where every read of this table lives, carries it. A tenant's failures
-- newest first, its one honest use, is the (tenant_id, succeeded = false) prefix of the first index.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one (V36). Delivery writes wait while the two indexes build.

CREATE INDEX idx_webhook_delivery_tenant_outcome
    ON core.webhook_delivery (tenant_id, succeeded, attempted_at DESC);

CREATE INDEX idx_webhook_delivery_endpoint_failed
    ON core.webhook_delivery (endpoint_id, attempted_at DESC)
    WHERE succeeded = FALSE;

DROP INDEX core.ix_webhook_delivery_failed;
