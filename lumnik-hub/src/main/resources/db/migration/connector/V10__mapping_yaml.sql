-- V10 — Add YAML override storage to connector_mapping for the Mapping Engine

ALTER TABLE connector.connector_mapping
  ADD COLUMN IF NOT EXISTS yaml        text,
  ADD COLUMN IF NOT EXISTS template_id text;

-- One override per (tenant, template). Prevents conflicting overrides.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'uq_mapping_tenant_template'
  ) THEN
    ALTER TABLE connector.connector_mapping
      ADD CONSTRAINT uq_mapping_tenant_template UNIQUE (tenant_id, template_id);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_mapping_template_tenant
  ON connector.connector_mapping(template_id, tenant_id);
