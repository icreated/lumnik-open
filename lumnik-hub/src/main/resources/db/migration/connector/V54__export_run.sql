-- V54__export_run.sql — kind:Export delivery ledger (tenant-bound, RLS-enforced, append-mostly)
CREATE TABLE connector.export_run (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        BIGINT      NOT NULL,
    export_id        UUID        NOT NULL,   -- export_manifest.id: kept by re-applies; the RunLock key
    export_name      TEXT        NOT NULL,
    scope            TEXT        NOT NULL,   -- effective spec.scope, copied from the stored manifest
    entity_name      TEXT        NOT NULL,
    destination_kind TEXT        NOT NULL,
    manifest_sha256  TEXT        NOT NULL,   -- which version of the definition ran
    trigger          TEXT        NOT NULL CHECK (trigger IN ('manual', 'schedule')),
    triggered_by     BIGINT,                 -- actor id; NULL for a scheduled run
    occurrence       TIMESTAMPTZ,            -- the cron slot; NULL for a manual run
    artifact_time    TIMESTAMPTZ NOT NULL,   -- the instant the date/timestamp name tokens resolve from
    attempt          INT         NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    retry_of         UUID        REFERENCES connector.export_run(id),
    root_run_id      UUID        REFERENCES connector.export_run(id),  -- the lineage's first run; NULL on it
    status           TEXT        NOT NULL CHECK (status IN ('Running', 'Completed', 'Failed')),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,
    row_count        BIGINT,
    byte_count       BIGINT,
    sha256           TEXT,
    location         TEXT,                   -- PublishedArtifact.location
    version_id       TEXT,                   -- s3 versioned buckets only
    etag             TEXT,                   -- s3 only; never a content hash
    failure_code     TEXT        CHECK (failure_code IN
                         ('definition', 'generation', 'publication', 'collision', 'interrupted')),
    failure_reason   TEXT,
    CHECK ((trigger = 'schedule') = (occurrence IS NOT NULL)),
    CHECK ((retry_of IS NULL) = (root_run_id IS NULL)),
    CHECK (status <> 'Running'   OR ended_at IS NULL),
    CHECK (status =  'Running'   OR ended_at IS NOT NULL),
    CHECK (status <> 'Completed' OR (row_count IS NOT NULL AND byte_count IS NOT NULL
                                     AND sha256 IS NOT NULL AND location IS NOT NULL
                                     AND failure_code IS NULL)),
    CHECK (status <> 'Failed'    OR (failure_code IS NOT NULL AND failure_reason IS NOT NULL))
);

-- At most one run in flight per export: covers a double click, two replicas, and a manual run racing the scheduler.
CREATE UNIQUE INDEX uq_export_run_in_flight
    ON connector.export_run (tenant_id, export_name) WHERE status = 'Running';
-- A scheduled occurrence is claimed exactly once by the scheduler (attempt 1)...
CREATE UNIQUE INDEX uq_export_run_occurrence_attempt
    ON connector.export_run (tenant_id, export_name, occurrence, attempt) WHERE occurrence IS NOT NULL;
-- ...and delivered at most once, however many retries it takes.
CREATE UNIQUE INDEX uq_export_run_occurrence_delivered
    ON connector.export_run (tenant_id, export_name, occurrence) WHERE status = 'Completed';
CREATE INDEX idx_export_run_recent
    ON connector.export_run (tenant_id, export_name, started_at DESC);
-- A retry's attempt number counts over its lineage: every run sharing COALESCE(root_run_id, id).
CREATE INDEX idx_export_run_lineage
    ON connector.export_run (tenant_id, (COALESCE(root_run_id, id)));

ALTER TABLE connector.export_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON connector.export_run
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE ON connector.export_run TO lumnik_app;
