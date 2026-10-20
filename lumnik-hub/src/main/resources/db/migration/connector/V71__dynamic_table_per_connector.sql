-- V71 — One registration per connector (#1186).
-- Two connectors of one tenant that read a source table of the same name write one hub table
-- under one mapping id. Keyed on (tenant_id, mapping_id), they shared ONE registry row held by
-- whichever wrote first: deleting the other found nothing to clean, and its rows outlived it.
-- Each connector now registers on its own first write. NULLS NOT DISTINCT keeps a registration
-- with no connector (internal paths) unique as before.

ALTER TABLE connector.dynamic_table DROP CONSTRAINT uq_dynamic_table_tenant_mapping;

ALTER TABLE connector.dynamic_table
  ADD CONSTRAINT uq_dynamic_table_tenant_mapping_connector
  UNIQUE NULLS NOT DISTINCT (tenant_id, mapping_id, connector_id);
