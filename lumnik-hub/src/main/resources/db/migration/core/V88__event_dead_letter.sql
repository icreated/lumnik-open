-- V88 — What an outbox consumer could not do, kept instead of lost (#1837).
--
-- The sweeper moves a consumer's cursor past an event whatever the consumer throws (no head-of-line blocking), so a
-- failure used to cost the event for good, with a log line to show for it. It now leaves a row here, in the transaction
-- that advances the cursor: the cursor never blocks, and nothing a consumer failed on is only a log line.
--
-- One row is the unit that can be replayed: (consumer, event) when the consumer failed before it knew its targets,
-- (consumer, event, target) when it failed on one of them — a Process, say, while its neighbours decided. The envelope is
-- a copy of the outbox row: a replay after the outbox's retention still has the event.
--
-- error is a short description (the SQLSTATE, else the exception's class), never its message: a message can quote a row.

CREATE TABLE core.event_dead_letter (
    id            BIGSERIAL    PRIMARY KEY,
    consumer_name TEXT         NOT NULL,
    event_id      BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL,
    -- '' = the consumer's whole handling of the event; else what failed ('process:<name>')
    target_key    TEXT         NOT NULL DEFAULT '',
    error         TEXT         NOT NULL,
    envelope      JSONB        NOT NULL,
    failed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at   TIMESTAMPTZ,
    CONSTRAINT event_dead_letter_once UNIQUE (consumer_name, event_id, target_key)
);

-- the operator's list, open or not: newest first
CREATE INDEX event_dead_letter_list_idx ON core.event_dead_letter (tenant_id, failed_at DESC, id DESC);
-- what the retention walks: the replayed ones by when they were, the open ones by when they failed
CREATE INDEX event_dead_letter_replayed_idx ON core.event_dead_letter (replayed_at) WHERE replayed_at IS NOT NULL;
CREATE INDEX event_dead_letter_open_age_idx ON core.event_dead_letter (failed_at) WHERE replayed_at IS NULL;

ALTER TABLE core.event_dead_letter ENABLE ROW LEVEL SECURITY;
-- the sweeper writes in system mode ('0', as for core.event_outbox, V4); everything else sees its own tenant
CREATE POLICY tenant_isolation ON core.event_dead_letter
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

COMMENT ON TABLE core.event_dead_letter IS
    'Outbox events a consumer failed on (#1837): the unit of replay is (consumer, event[, target]); the cursor has moved past them.';
