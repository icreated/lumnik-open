-- V67__webhook_delivery_retention_index.sql — the delivery retention purge reads its cutoff through an index (#940).
--
-- WebhookDeliveryRetentionPurger deletes the oldest finished deliveries once a day:
-- WHERE attempted_at < cutoff ORDER BY attempted_at LIMIT n. No index led with attempted_at
-- (ix_webhook_delivery_endpoint and V64's two lead with endpoint_id / tenant_id), so each batch
-- would read the whole table to find the rows that expired — the reason V40 gave the audit log
-- idx_audit_event_occurred_at. This one lets the batch start at the oldest row and stop at its LIMIT.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one (V36). Delivery writes wait while it builds.

CREATE INDEX idx_webhook_delivery_attempted_at
    ON core.webhook_delivery (attempted_at);
