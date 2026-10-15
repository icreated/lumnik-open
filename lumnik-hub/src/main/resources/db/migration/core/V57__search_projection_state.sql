-- V57__search_projection_state.sql — per (tenant, SearchIndex) worker state (#671), and closing
-- V56's now-unreachable MarkKind.PURGE at the schema level (design doc §1).

-- No production code ever wrote mark_kind = 'PURGE' (§1), but a migration that assumes its own
-- precondition instead of enforcing it is asking for trouble. A PURGE row's own meaning is "wipe this
-- whole index" — dropping it outright would silently abandon that obligation, so any that exist are
-- migrated into the table that already carries the same meaning today (core.search_index_purge, V55,
-- drained by §5d) before this table stops being able to represent them at all.
INSERT INTO core.search_index_purge (tenant_id, name, entity, requested_at)
SELECT tenant_id, search_index, entity, marked_at
FROM core.search_projection_mark WHERE mark_kind = 'PURGE';

DELETE FROM core.search_projection_mark WHERE mark_kind = 'PURGE';

ALTER TABLE core.search_projection_mark DROP CONSTRAINT search_projection_mark_kind_check;
ALTER TABLE core.search_projection_mark
    ADD CONSTRAINT search_projection_mark_kind_check CHECK (mark_kind IN ('KEY', 'ALL'));

-- The re-mark decision (design doc §1, #671): ON CONFLICT DO UPDATE on this table now requires
-- UPDATE privilege on the columns it sets, checked structurally by Postgres whenever the clause is
-- present in the statement -- not only when a row actually conflicts. SearchIndexStore.upsert writes
-- marks on the request-scoped (lumnik_app) connection, so lumnik_app needs UPDATE on exactly the two
-- columns the re-mark SET clause touches -- id and marked_at -- and nothing else: canonical_key,
-- mark_kind, tenant_id, search_index, entity and reason stay append-only for this role.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT UPDATE (id, marked_at) ON core.search_projection_mark TO lumnik_app;
  END IF;
END $$;

-- Per (tenant, SearchIndex) worker state (#671).
--
-- The data #703 exposes as-is: which provider, live generation, drain position, status, retry count,
-- last error. "Lag" is not stored here — it is computed live as now() - the oldest undrained mark's
-- marked_at (zero when none remain), the same posture core.search_index's own status/invalid_reason
-- pair takes for "state" versus a derived read.

CREATE TABLE core.search_projection_state (
    tenant_id       BIGINT      NOT NULL,
    search_index    TEXT        NOT NULL,       -- core.search_index.name
    provider_name   TEXT,                        -- the ProjectionSink.name() live_generation belongs to;
                                                   -- NULL before the first successful build, same as
                                                   -- live_generation itself
    live_generation TEXT,                        -- provider-opaque; NULL before the first successful build
    position        BIGINT      NOT NULL DEFAULT 0,  -- highest core.search_projection_mark.id fully applied
    status          TEXT        NOT NULL DEFAULT 'building',
    retry_count     INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, search_index),
    CONSTRAINT search_projection_state_status_check
        CHECK (status IN ('building', 'ready', 'degraded')),
    CONSTRAINT search_projection_state_error_check
        CHECK ((status = 'degraded') = (last_error IS NOT NULL)),
    CONSTRAINT search_projection_state_provider_check
        CHECK ((provider_name IS NULL) = (live_generation IS NULL))
);

ALTER TABLE core.search_projection_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.search_projection_state
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- The worker owns this table end to end (admin pool, like SearchIndexRevalidator's own state
-- writes) — no request path ever touches it, so lumnik_app gets a read grant for #703's status
-- door and nothing else. The default-privileges baseline (infra/postgres/init/01-roles.sh) grants
-- every new table's INSERT/UPDATE/DELETE to lumnik_app too, so an explicit REVOKE is required here
-- to make "nothing else" true, exactly the pattern V55/V56 already established on their own tables.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT SELECT ON core.search_projection_state TO lumnik_app;
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON core.search_projection_state FROM lumnik_app;
  END IF;
END $$;

-- SearchProjectionScheduler's discoveryTick and SearchProjectionReconciler's sweep both query
-- core.search_index WHERE status = 'valid' every minute, independently -- neither had an index
-- covering that predicate before this table grew whichever kind of large a real deployment does.
CREATE INDEX search_index_valid_idx ON core.search_index (tenant_id) WHERE status = 'valid';

-- purgeDrain's own starvation guard (code review, #671): fetchUndrainedPurgeRows orders by this
-- column (NULLS FIRST) ahead of id, so a row that keeps failing (a ProjectionSink outage, a
-- transient DB blip) demotes itself behind every not-yet-attempted row on each failed attempt,
-- rather than permanently occupying the front of the FIFO queue if >= PURGE_BATCH_SIZE rows are
-- ever simultaneously stuck. Only SearchProjectionWorker (admin datasource, the table owner) ever
-- writes it -- lumnik_app stays append-only-via-INSERT here, same as V55's own posture.
ALTER TABLE core.search_index_purge ADD COLUMN last_attempted_at TIMESTAMPTZ;
