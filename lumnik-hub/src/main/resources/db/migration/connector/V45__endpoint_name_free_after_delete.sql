-- V45 — A deleted endpoint stops holding its name. Same defect as V44, second table.
--
-- `lm endpoint delete` is a SOFT delete (EndpointResource.delete → softDelete()), and the upsert
-- that puts an endpoint back looks its name up LIVE (ConnectorEndpointRepository.findByName filters
-- deletedAt is null) — so re-applying the manifest builds a NEW row under the same (connector_id,
-- name). UNIQUE (connector_id, name) counted the tombstone: the apply died at commit with 23505,
-- the integrator got an anonymous 500 for a manifest that had just worked, and the endpoint could
-- never come back under its own name.
--
-- idx_endpoint_connector goes with it, for the same reason V44 drops idx_secret_name: the new index
-- has the same leading column and the same predicate, so it serves every lookup the old one did.
-- This migration is what makes it redundant — before it, (connector_id) WHERE deleted_at IS NULL was
-- the only index on that column — so leaving it would be this edit's own leftover, not a prior one.

ALTER TABLE connector.connector_endpoint DROP CONSTRAINT connector_endpoint_connector_id_name_key;
CREATE UNIQUE INDEX idx_endpoint_name ON connector.connector_endpoint (connector_id, name)
    WHERE deleted_at IS NULL;
DROP INDEX connector.idx_endpoint_connector;
