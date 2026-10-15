-- V65__event_outbox_decisions_index.sql — the inbox walks the tenant's decisions, not its whole log (#901).
--
-- The inbox (InboxResource.list; `lm`'s :inbox polls it every 5 s, the PWA reads it on every open)
-- reads WHERE tenant_id = ? AND event_type LIKE 'decision.%' ORDER BY id DESC LIMIT n. V36 served
-- it with (tenant_id, id DESC), on the premise that the LIKE is a cheap filter over a tiny LIMIT
-- window. It is not: a tenant has decisions only if data.row.changed flows (ProcessConsumer reacts
-- to nothing else), so its log is mostly row events, and the walk filters them out one by one until
-- it has n decisions. The default read keeps only un-acked decisions; once fewer than n are left —
-- an inbox someone keeps up with — the walk never fills its LIMIT and reads the tenant's whole
-- retained log on every poll.
--
-- A partial index over the decisions alone makes the walk read decisions only. Row events never
-- enter it (an insert only evaluates the predicate), so the write path is unchanged; its predicate
-- repeats the query's LIKE, which is what lets the planner use it. (tenant_id, id DESC) stays: the
-- SSE stream's MAX(id) per tenant reads it.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one (V36).

CREATE INDEX idx_event_outbox_decisions ON core.event_outbox (tenant_id, id DESC)
    WHERE event_type LIKE 'decision.%';
