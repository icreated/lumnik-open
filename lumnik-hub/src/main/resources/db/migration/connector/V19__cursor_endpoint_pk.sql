-- Re-key the per-connector cursor to be per-ENDPOINT.
--
-- A connector may own many endpoints (declarative multi-table manifest: one connection,
-- N tables). Each endpoint has its own cursor. The original PK was connector_id, so a second
-- endpoint of the same connector collided on INSERT ("duplicate key ... connector_cursor_pkey").
-- V18 added endpoint_id as a column + unique index but left the PK on connector_id — this
-- completes the move: endpoint_id is the identity. connector_id stays as a back-reference.
DELETE FROM connector.connector_cursor WHERE endpoint_id IS NULL;
ALTER TABLE connector.connector_cursor DROP CONSTRAINT connector_cursor_pkey;
ALTER TABLE connector.connector_cursor ALTER COLUMN endpoint_id SET NOT NULL;
ALTER TABLE connector.connector_cursor ADD CONSTRAINT connector_cursor_pkey PRIMARY KEY (endpoint_id);
DROP INDEX IF EXISTS connector.uq_cursor_endpoint;
