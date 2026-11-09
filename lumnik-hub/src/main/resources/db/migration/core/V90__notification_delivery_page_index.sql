-- V90 — The mail deliveries page reads through an index (#1902).
--
-- core.notification_delivery gets one row per event and rule, and the page that lists them
-- (GET /api/platform/notification-deliveries — `lm notify deliveries`) reads a tenant's rows given-up first, then
-- retrying, then sent, newest first: WHERE tenant_id = ? ORDER BY <rank>, COALESCE(attempted_at, created_at) DESC,
-- id DESC LIMIT n (NotificationDeliveries.list). No index led with tenant_id and carried that order, so every page
-- scanned the whole table and sorted the tenant's history — the defect V64 closed for the webhook page (#902).
--
-- The two expressions below are the ones the query writes, character for character in meaning: the planner matches
-- an index expression to the query's by its tree, so a change to the query's ORDER BY is made here too. The index
-- holds the page's exact order, the planner walks it and stops at the LIMIT.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction (V36). Delivery writes wait
-- while it builds.

CREATE INDEX idx_notification_delivery_page ON core.notification_delivery (
    tenant_id,
    (CASE WHEN succeeded THEN 2 WHEN attempts >= 3 THEN 0 ELSE 1 END),
    (COALESCE(attempted_at, created_at)) DESC,
    id DESC);
