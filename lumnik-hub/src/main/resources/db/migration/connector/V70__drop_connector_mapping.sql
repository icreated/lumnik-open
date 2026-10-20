-- V70 — Drop the mapping override storage (#1174).
--
-- connector.connector_mapping held the per-tenant YAML overrides of the classpath mapping
-- templates (V10). Nothing a run executes ever read them: no template shipped, and ingestion
-- resolved mappings through an identity stub. The template/override surface — the registry, the
-- resolver, /api/platform/mappings, `lm mapping` and `:mappings` — is removed with this migration.
-- The mapping a run executes is built from the connector's own config by its type; that path does
-- not touch this table.
--
-- connector.connector.mapping_id was a soft reference into this table (no FK, V9) and no code reads
-- it, so it goes with it rather than pointing at nothing.
--
-- Its RLS policy (V9, V21, V22) and its indexes and constraints (V10) are dropped with the table.

ALTER TABLE connector.connector DROP COLUMN IF EXISTS mapping_id;

DROP TABLE IF EXISTS connector.connector_mapping;
