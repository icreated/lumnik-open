-- Optimistic-lock guard for the per-connector cursor.
--
-- The cursor is a single row keyed by connector_id, shared by every run of that connector.
-- Without a version, two overlapping runs do a blind read-modify-write and a stale writer can
-- REWIND the cursor (lost update) -> the engine re-reads the same pages without bound (runaway).
-- @Version makes Hibernate emit `... WHERE version = ?` and increment it, so the second of two
-- concurrent writers fails with an optimistic-lock error instead of silently clobbering.
--
-- This is the defense-in-depth layer; the primary guard is a per-connector advisory lock that
-- prevents two runs of the same connector from overlapping in the first place.
ALTER TABLE connector.connector_cursor
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
