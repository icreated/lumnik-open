-- V87 — The work of a lookup fan-out, durable and resumable (#1834).
--
-- A change in a lookup table (joins:) of a match: Entity re-evaluates every fused row the lookup row reaches: the number
-- of rows is the business meaning and is never capped, so the work is cut into pages instead. One row here is one
-- (process, source event, impact target): the process consumer writes it and moves on, a worker takes the row a page of
-- canonical keys at a time, in a transaction that also writes the decisions and advances after_canonical_key. A crash
-- resumes at the next page; a page replayed is harmless (the decision's dedup key).
--
-- An internal, mutable task — not a published fact, so not an outbox event: a subscription on '*' would see one event per
-- page, and the page size is a technical choice. It is a queue bounded to resuming lookup fan-out, not a change feed.
--
-- Admin-only, as core.event_consumer is: the consumer and the worker both run on the admin pool with the tenant in every
-- predicate; lumnik_app is granted nothing.

CREATE TABLE core.process_fanout (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    -- a process removed takes its pending work with it
    process_id          UUID         NOT NULL REFERENCES core.process_manifest (id) ON DELETE CASCADE,
    source_event_id     BIGINT       NOT NULL,                 -- core.event_outbox.id of the lookup change
    -- which join of which Entity, from which source: one lookup event can match several joins of one Entity
    impact_target       TEXT         NOT NULL,
    lookup_key          TEXT         NOT NULL,                 -- the changed lookup row's key, as the join compares it
    after_canonical_key TEXT,                                  -- keyset cursor; NULL = nothing done yet
    status              TEXT         NOT NULL DEFAULT 'pending',
    affected_count      BIGINT,                                -- counted once, when the work starts
    processed_count     BIGINT       NOT NULL DEFAULT 0,
    attempts            INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT process_fanout_status_check CHECK (status IN ('pending', 'waiting-index', 'running', 'done', 'failed')),
    CONSTRAINT process_fanout_once UNIQUE (process_id, source_event_id, impact_target)
);

-- what the worker claims: the open rows, oldest first
CREATE INDEX process_fanout_open_idx ON core.process_fanout (id) WHERE status IN ('pending', 'waiting-index', 'running');
-- what the purge of finished rows walks
CREATE INDEX process_fanout_done_idx ON core.process_fanout (updated_at) WHERE status = 'done';

ALTER TABLE core.process_fanout ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.process_fanout
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

COMMENT ON TABLE core.process_fanout IS
    'Durable work of a lookup fan-out (#1834): one row per (process, source event, impact target), drained a page of canonical keys at a time.';

-- Admin-only is a grant, not a hope: the default privileges of lumnik_admin hand lumnik_app every table it creates.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    REVOKE ALL ON core.process_fanout FROM lumnik_app;
  END IF;
END $$;
