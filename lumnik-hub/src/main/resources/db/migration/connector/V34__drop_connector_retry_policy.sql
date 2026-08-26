-- V34: Drop connector.connector.retry_policy — a knob no engine ever read.
-- The column (V9) was mapped by the Connector entity through RetryPolicyConverter, but nothing in
-- the ingestion path ever consulted the value: the only live retry configuration is the REST engine's
-- own io.lumnik.hub.connector.rest.reliability.RetryPolicy, built per endpoint and never persisted.
-- The entity field, the api/types record and its converter are removed with this migration.

ALTER TABLE connector.connector DROP COLUMN IF EXISTS retry_policy;
