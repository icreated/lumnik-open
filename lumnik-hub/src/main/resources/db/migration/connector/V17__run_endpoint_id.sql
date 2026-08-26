-- Add endpoint_id to connector_run so each run is associated with the endpoint
-- it executed. Nullable during migration; backfilled by FlatConnectorEndpointMigration.
ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS endpoint_id uuid;

CREATE INDEX IF NOT EXISTS idx_run_endpoint ON connector.connector_run(endpoint_id);

-- Replace the old tenant-keyed in-flight index with an endpoint-keyed one.
DROP INDEX IF EXISTS connector.idx_run_open;
CREATE INDEX IF NOT EXISTS idx_run_open ON connector.connector_run(endpoint_id) WHERE status = 'Running';
