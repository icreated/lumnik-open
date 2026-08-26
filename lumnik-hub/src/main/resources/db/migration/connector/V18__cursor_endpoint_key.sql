-- Add endpoint_id to connector_cursor as the future lookup key.
-- Nullable during migration; FlatConnectorEndpointMigration fills it at startup
-- before the engine starts serving traffic.
ALTER TABLE connector.connector_cursor ADD COLUMN endpoint_id uuid;

-- Unique index enforces one cursor per endpoint once the column is populated.
-- Partial (WHERE endpoint_id IS NOT NULL) so legacy rows with null don't conflict.
CREATE UNIQUE INDEX uq_cursor_endpoint ON connector.connector_cursor(endpoint_id)
    WHERE endpoint_id IS NOT NULL;
