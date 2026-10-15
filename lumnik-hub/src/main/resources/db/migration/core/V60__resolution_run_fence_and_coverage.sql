-- V60__resolution_run_fence_and_coverage.sql — #748: lets a resolution: entity's search asOf follow catch-up.
--
-- resolution_fence_at: a per-run lower bound on the database clock (the clock that stamps _ingested_at),
-- taken inside the INSERT of the run row, i.e. before the run's first chunk transaction starts. NULL for
-- every run that predates this migration: the DEFAULT is set AFTER the column exists, otherwise ADD COLUMN
-- would stamp every historical run with the migration instant. A NULL fence means "unknown bound".
ALTER TABLE connector.connector_run ADD COLUMN resolution_fence_at timestamptz;
ALTER TABLE connector.connector_run ALTER COLUMN resolution_fence_at SET DEFAULT clock_timestamp();

-- resolution_exempt: the run never executed — it was skipped (another run holds the endpoint's advisory
-- lock) or failed to take that lock — so it wrote nothing and there is nothing to resolve. Without this,
-- every scheduler re-fire or manual+schedule overlap leaves a Cancelled row that no pass ever covers and
-- pins every resolution: entity's asOf. Set by ConnectorEngine.finalizeRun, never by a run that started.
ALTER TABLE connector.connector_run ADD COLUMN resolution_exempt boolean NOT NULL DEFAULT false;

-- earliest uncovered fence (per tick)
CREATE INDEX idx_run_resolution_fence ON connector.connector_run (tenant_id, resolution_fence_at)
    WHERE resolution_fence_at IS NOT NULL;
-- "is there an uncovered run whose bound is unknown" (per tick)
CREATE INDEX idx_run_resolution_fence_unknown ON connector.connector_run (tenant_id)
    WHERE resolution_fence_at IS NULL;

-- Coverage: this entity's resolution pass (or a replay's frozen snapshot of finished runs) has been
-- through everything that run ingested. Written on the admin pool only.
CREATE TABLE core.resolution_run_coverage (
    tenant_id   BIGINT      NOT NULL,
    entity_type TEXT        NOT NULL,   -- lower-cased entity name, as core.entity_identity.entity_type
    run_id      UUID        NOT NULL REFERENCES connector.connector_run(id) ON DELETE CASCADE,
    covered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_resolution_run_coverage PRIMARY KEY (tenant_id, entity_type, run_id)
);
ALTER TABLE core.resolution_run_coverage ENABLE ROW LEVEL SECURITY;
CREATE POLICY resolution_run_coverage_tenant_isolation ON core.resolution_run_coverage
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.resolution_run_coverage IS
    'Which connector runs an entity''s resolution pass has covered; bounds the search asOf of resolution: entities (#748).';
