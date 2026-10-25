-- V76 — Whether kind:Process has evaluated a run's rows (#1351), with no clock in the answer.
--
-- 1. A run records the outbox's high-water mark when it ends. Every row event (data.row.changed) the
--    run wrote sits at or under it; its own connector.run.* event comes after. The 'process'
--    consumer has read the run's rows once its cursor (core.event_consumer) reaches it.
-- 2. The cursor says the rows were read, not by which Processes. Every write to a process takes a
--    fresh revision from one sequence, and a run records, when it starts, the fingerprint of its
--    tenant's revisions. The same fingerprint later means no Process was applied, re-applied,
--    enabled, disabled or removed since: the rows were read by the set the tenant has now.
--
-- NULL on both run columns for every run before this migration. Existing processes take a revision
-- each from the default; no rewrite of connector_run.

CREATE SEQUENCE IF NOT EXISTS core.process_manifest_revision_seq;
ALTER TABLE core.process_manifest
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT nextval('core.process_manifest_revision_seq');
ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS outbox_high_water BIGINT;
ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS process_set TEXT;
