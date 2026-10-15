-- V56__search_projection_mark.sql — the durable, idempotent change feed the projection drains (#701).
--
-- Two tables. `search_projection_mark` is the mark itself: one row per (tenant, search index,
-- mark kind, canonical key), produced on the request/ingestion path and cleared only by #671's
-- admin-pool consumer — append-only for lumnik_app, the same posture as core.search_index_purge
-- (V55). `search_projection_mark_watermark` is pure derived bookkeeping for the run-level ingestion
-- scan (D8a): losing or rewinding it only costs a re-scan, never a correctness gap, so lumnik_app
-- keeps UPDATE on it, unlike the mark table.

CREATE TABLE core.search_projection_mark (
    id             BIGSERIAL   PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    search_index   TEXT        NOT NULL,      -- core.search_index.name
    entity         TEXT        NOT NULL,      -- denormalized: #671 must not lose the target if the
                                               -- declaration is deleted before the mark is drained
    mark_kind      TEXT        NOT NULL,      -- 'KEY' | 'ALL' | 'PURGE'
    canonical_key  TEXT        NOT NULL DEFAULT '',  -- '' for ALL / PURGE; never NULL (NULL breaks
                                                       -- the uniqueness guarantee below — Postgres
                                                       -- treats NULLs as distinct)
    reason         TEXT        NOT NULL,      -- trigger name, for observability (ingestion-run,
                                               -- resolution-decision, entity-reapply, index-apply,
                                               -- entity-delete, tenant-delete)
    marked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT search_projection_mark_kind_check CHECK (mark_kind IN ('KEY', 'ALL', 'PURGE')),
    -- Both directions, not just "non-KEY implies empty": a KEY mark with an EMPTY canonical_key
    -- previously slipped through (a Copilot review on PR #709 caught it) and would have collided,
    -- under the UNIQUE below, with whichever ALL/PURGE mark already claims that same
    -- (tenant, search_index, mark_kind='KEY', '') slot's — nonsensical, and disagreeing with the
    -- Java-side guard SearchProjectionMark's constructor already enforces (SearchProjectionMarkTest).
    CONSTRAINT search_projection_mark_key_check
        CHECK ((mark_kind = 'KEY') = (canonical_key <> '')),
    UNIQUE (tenant_id, search_index, mark_kind, canonical_key)
);

CREATE INDEX search_projection_mark_drain_idx ON core.search_projection_mark (tenant_id, search_index, id);

ALTER TABLE core.search_projection_mark ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.search_projection_mark
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- Same posture as core.search_index_purge (V55): produced on the request path, cleared only by
-- #671's admin-pool consumer. Guarded on the role's existence, like V47/V55.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT SELECT, INSERT ON core.search_projection_mark TO lumnik_app;
    GRANT USAGE ON SEQUENCE core.search_projection_mark_id_seq TO lumnik_app;
    REVOKE UPDATE, DELETE, TRUNCATE ON core.search_projection_mark FROM lumnik_app;
  END IF;
END $$;

-- The run-level watermark for D8(a): one row per (tenant_id, table) pair that at least one match:
-- entity sources and that at least one SearchIndex currently indexes. Advanced only after a
-- successful scan-and-mark pass, never past the run's own endedAt.
CREATE TABLE core.search_projection_mark_watermark (
    tenant_id   BIGINT      NOT NULL,
    table_name  TEXT        NOT NULL,   -- schema-qualified connector.t_* name
    watermark   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, table_name)
);

ALTER TABLE core.search_projection_mark_watermark ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON core.search_projection_mark_watermark
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- Same role-existence guard as the mark table above, for the same reason: an environment without
-- lumnik_app yet (a fresh dev/test bootstrap ordering) must not fail this migration outright.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    GRANT SELECT, INSERT, UPDATE ON core.search_projection_mark_watermark TO lumnik_app;
  END IF;
END $$;
