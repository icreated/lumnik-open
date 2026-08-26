-- V11 — Registry of dynamic tables created by the mapping engine.
-- Tracks per-tenant per-mapping schema for DriftDetector + lm data tables listing.

CREATE TABLE IF NOT EXISTS connector.dynamic_table (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       BIGINT NOT NULL,
    mapping_id      TEXT NOT NULL,
    table_schema    TEXT NOT NULL,
    table_name      TEXT NOT NULL,
    schema_inferred JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_dynamic_table_tenant_mapping') THEN
    ALTER TABLE connector.dynamic_table
      ADD CONSTRAINT uq_dynamic_table_tenant_mapping UNIQUE (tenant_id, mapping_id);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dynamic_table_tenant
  ON connector.dynamic_table(tenant_id);

ALTER TABLE connector.dynamic_table ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname = 'dynamic_table_tenant_isolation') THEN
    CREATE POLICY dynamic_table_tenant_isolation ON connector.dynamic_table
      USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
  END IF;
END $$;

GRANT SELECT, INSERT, UPDATE ON connector.dynamic_table TO lumnik_app;
