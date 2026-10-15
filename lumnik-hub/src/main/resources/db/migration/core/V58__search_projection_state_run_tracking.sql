-- V58__search_projection_state_run_tracking.sql — on-demand rebuild trigger, cooperative cancel,
-- crash recovery run-tracking (#703 design doc §2).

ALTER TABLE core.search_projection_state
    ADD COLUMN active_run_id          UUID,
    ADD COLUMN active_run_phase       TEXT,       -- 'building' | 'publishing'
    ADD COLUMN active_run_started_at  TIMESTAMPTZ,
    ADD COLUMN active_generation      TEXT,
    ADD COLUMN active_provider_name   TEXT,
    ADD COLUMN cancel_requested_run_id UUID,
    ADD COLUMN last_rebuild_run_id       UUID,
    ADD COLUMN last_rebuild_outcome      TEXT,     -- 'ready' | 'failed' | 'cancelled'
    ADD COLUMN last_rebuild_finished_at  TIMESTAMPTZ,
    ADD COLUMN last_document_count       BIGINT,
    ADD COLUMN last_document_count_at    TIMESTAMPTZ;

ALTER TABLE core.search_projection_state
    ADD CONSTRAINT search_projection_state_active_run_check
        CHECK ((active_run_id IS NULL) = (active_run_phase IS NULL)
           AND (active_run_id IS NULL) = (active_run_started_at IS NULL)),
    ADD CONSTRAINT search_projection_state_active_run_phase_check
        CHECK (active_run_phase IS NULL OR active_run_phase IN ('building', 'publishing')),
    ADD CONSTRAINT search_projection_state_active_generation_check
        CHECK ((active_generation IS NULL) = (active_provider_name IS NULL)
           AND (active_generation IS NULL OR active_run_id IS NOT NULL)),
    ADD CONSTRAINT search_projection_state_cancel_requested_check
        CHECK (cancel_requested_run_id IS NULL
           OR (active_run_id IS NOT NULL AND cancel_requested_run_id = active_run_id)),
    ADD CONSTRAINT search_projection_state_last_rebuild_check
        CHECK ((last_rebuild_run_id IS NULL) = (last_rebuild_outcome IS NULL)
           AND (last_rebuild_outcome IS NULL) = (last_rebuild_finished_at IS NULL)
           AND (last_rebuild_outcome IS NULL OR last_rebuild_outcome IN ('ready', 'failed', 'cancelled')));

ALTER TABLE core.search_projection_state DROP CONSTRAINT search_projection_state_status_check;
ALTER TABLE core.search_projection_state
    ADD CONSTRAINT search_projection_state_status_check
        CHECK (status IN ('building', 'ready', 'degraded', 'cancelled'));

-- Same read-only posture as V57's own columns: the worker (admin pool) owns every write to this
-- table, including these new columns; lumnik_app only ever reads them (#703's own status door).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT SELECT ON core.search_projection_state TO lumnik_app;
  END IF;
END $$;
