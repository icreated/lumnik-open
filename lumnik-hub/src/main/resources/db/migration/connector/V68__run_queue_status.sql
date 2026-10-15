-- V68__run_queue_status.sql — a durable 'Queued' status for a run accepted before it may start (#952).
--
-- Slice 1 emits nothing: this widens what the two run tables accept, so every replica can read a
-- Queued row before lumnik.run-queue.enabled is ever turned on. Written to be re-runnable
-- (IF [NOT] EXISTS, drop-then-add), because RunQueueMigrationIT replays this very text.

-- ── connector_run ──────────────────────────────────────────────────────────────────────────────
-- One in-flight run per endpoint was only ever held by the RunLock. Before the unique index can exist,
-- end every Running row that a more recent Running row of the same endpoint supersedes: the lock makes
-- "a newer Running row exists" mean the older one had already lost it. Ended the way RunReaper ends a
-- dead run (Failed + a reason) and NOT resolution_exempt: it started, and may have written.
UPDATE connector.connector_run r
   SET status = 'Failed', ended_at = now(),
       error_message = 'superseded while enforcing one in-flight run per endpoint'
 WHERE r.status = 'Running' AND r.endpoint_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM connector.connector_run n
                WHERE n.endpoint_id = r.endpoint_id AND n.status = 'Running'
                  AND (n.started_at, n.id) > (r.started_at, r.id));

ALTER TABLE connector.connector_run DROP CONSTRAINT IF EXISTS connector_run_status_check;
ALTER TABLE connector.connector_run ADD CONSTRAINT connector_run_status_check
    CHECK (status IN ('Queued', 'Running', 'Completed', 'Partial', 'Failed', 'Cancelled'));

-- started_at is NULL while Queued and set once on leaving it (the start, or the ending of a run that
-- never ran, as ended_at). queued_at is the accept instant; definition_sha256 the witness of what was accepted.
ALTER TABLE connector.connector_run ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS queued_at timestamptz;
ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS definition_sha256 text;
ALTER TABLE connector.connector_run DROP CONSTRAINT IF EXISTS connector_run_started_at_check;
ALTER TABLE connector.connector_run ADD CONSTRAINT connector_run_started_at_check
    CHECK ((status = 'Queued') = (started_at IS NULL));
-- An expiry compares queued_at to a cutoff, and NULL < cutoff is never true: a Queued row without its
-- accept instant would never expire.
ALTER TABLE connector.connector_run DROP CONSTRAINT IF EXISTS connector_run_queued_at_check;
ALTER TABLE connector.connector_run ADD CONSTRAINT connector_run_queued_at_check
    CHECK (status <> 'Queued' OR queued_at IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS uq_connector_run_in_flight
    ON connector.connector_run (endpoint_id) WHERE status IN ('Queued', 'Running');
CREATE INDEX IF NOT EXISTS idx_run_queued
    ON connector.connector_run (queued_at, id) WHERE status = 'Queued';

-- ── export_run ─────────────────────────────────────────────────────────────────────────────────
-- Three CHECKs change: the status set and the two ended_at rules (Queued, like Running, has not ended).
-- The two ended_at rules are unnamed table CHECKs, so they are found by what they say. The Completed
-- and Failed receipt CHECKs mention neither ended_at nor the status set, and stay.
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'connector.export_run'::regclass AND contype = 'c'
              AND (conname = 'export_run_status_check' OR pg_get_constraintdef(oid) LIKE '%ended_at%')
  LOOP
    EXECUTE format('ALTER TABLE connector.export_run DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

ALTER TABLE connector.export_run ADD CONSTRAINT export_run_status_check
    CHECK (status IN ('Queued', 'Running', 'Completed', 'Failed'));
ALTER TABLE connector.export_run ADD CONSTRAINT export_run_open_ended_at_check
    CHECK ((status IN ('Queued', 'Running')) = (ended_at IS NULL));

ALTER TABLE connector.export_run ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE connector.export_run ADD COLUMN IF NOT EXISTS queued_at timestamptz;
ALTER TABLE connector.export_run DROP CONSTRAINT IF EXISTS export_run_started_at_check;
ALTER TABLE connector.export_run ADD CONSTRAINT export_run_started_at_check
    CHECK ((status = 'Queued') = (started_at IS NULL));
ALTER TABLE connector.export_run DROP CONSTRAINT IF EXISTS export_run_queued_at_check;
ALTER TABLE connector.export_run ADD CONSTRAINT export_run_queued_at_check
    CHECK (status <> 'Queued' OR queued_at IS NOT NULL);

-- A queued export counts as in flight: at most one Queued|Running run per export.
DROP INDEX IF EXISTS connector.uq_export_run_in_flight;
CREATE UNIQUE INDEX uq_export_run_in_flight
    ON connector.export_run (tenant_id, export_name) WHERE status IN ('Queued', 'Running');
CREATE INDEX IF NOT EXISTS idx_export_run_queued
    ON connector.export_run (queued_at, id) WHERE status = 'Queued';
