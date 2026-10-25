-- V85 — An event cursor reads only what is decided (#1570).
--
-- core.event_outbox.id is a BIGSERIAL: assigned at INSERT, visible at COMMIT. Events are written
-- inside the transaction that produces them, so when two of them overlap, the one holding the LOWER
-- id can commit after a reader has read the higher one and moved its id cursor past it. Every reader
-- cursored on id — the live tail and the webhook, email and process consumers — skipped that event.
--
-- Each row now records the transaction that inserted it. A reader keeps a cursor (xact_id, id) and
-- reads only rows whose transaction is older than the oldest one still open
-- (pg_snapshot_xmin(pg_current_snapshot())): those transactions are decided, no row of theirs can
-- appear later, so nothing at or below the cursor can still arrive.
--
-- Stored as BIGINT: an xid8 fits (the epoch-extended id stays far below 2^63), and every reader and
-- writer then speaks the type JDBC and JPA already map.
--
-- Rows written before this migration take xact_id 1 (the ADD COLUMN default, kept as their stored
-- value); every later row takes its real transaction id, always above 2. The consumers' cursors
-- take 1 as well, so (1, last_event_id) stands exactly where each consumer was: past the old rows it
-- had read, before the old rows it had not, before every new row.

ALTER TABLE core.event_outbox ADD COLUMN xact_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE core.event_outbox ALTER COLUMN xact_id SET DEFAULT (pg_current_xact_id()::text::bigint);

-- The durable consumers read every tenant in (xact_id, id) order; the live tail reads one tenant's.
CREATE INDEX idx_event_outbox_decided ON core.event_outbox (xact_id, id);
CREATE INDEX idx_event_outbox_tenant_decided ON core.event_outbox (tenant_id, xact_id, id);

ALTER TABLE core.event_consumer ADD COLUMN last_xact_id BIGINT NOT NULL DEFAULT 1;

-- A run's mark (V76) becomes the same pair: the process consumer has read the run's rows once its
-- cursor reaches (outbox_high_water_xact, outbox_high_water). A run that ended before this migration
-- has no xact half: its rows were all written before, at xact_id 1, and the evaluation reads it so.
ALTER TABLE connector.connector_run ADD COLUMN outbox_high_water_xact BIGINT;
