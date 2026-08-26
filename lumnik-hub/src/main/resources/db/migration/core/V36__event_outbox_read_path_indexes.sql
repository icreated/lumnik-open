-- core.event_outbox has two hot read paths, neither of which any existing index could serve.
--
-- 1. The SSE stream (EventStreamResource#fetchSince) polls continuously with
--        WHERE created_at > ? ORDER BY created_at LIMIT 500
--    The only created_at index before this migration was V1's
--        (published_at, created_at) WHERE published_at IS NULL
--    a PARTIAL index: this query carries no `published_at IS NULL` predicate, so the planner
--    cannot use it. Every poll therefore scanned and sorted the whole table.
--
-- 2. The Inbox (InboxResource) reads a tenant's decision tail with
--        WHERE tenant_id = ? AND event_type LIKE 'decision.%' ORDER BY id DESC LIMIT ?
--    uq_event_outbox_dedup (tenant_id, dedup_key) already narrows to the tenant, so this was
--    never a full-table scan — but it still sorted the tenant's whole history on every open.
--    Leading with tenant_id and carrying id DESC lets the planner walk the index backwards and
--    stop at LIMIT, with no sort at all. event_type is deliberately NOT in the index: a
--    LIKE 'decision.%' prefix match is a cheap filter over an already-tiny LIMIT window, and
--    adding it would widen every insert for no gain.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one.

CREATE INDEX IF NOT EXISTS idx_event_outbox_created_at
    ON core.event_outbox (created_at);

CREATE INDEX IF NOT EXISTS idx_event_outbox_tenant_id
    ON core.event_outbox (tenant_id, id DESC);
