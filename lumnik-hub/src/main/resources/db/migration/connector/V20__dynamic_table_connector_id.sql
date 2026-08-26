-- Link each ingested hub table to its owning connector, so SchemaCard discovery can resolve
-- scope (connector tag) → connectors → their hub tables directly. The old discovery joined
-- connector.connector_mapping, which the declarative endpoint/dump path does not populate;
-- this gives a direct, model-agnostic link.
ALTER TABLE connector.dynamic_table ADD COLUMN connector_id uuid;
CREATE INDEX idx_dynamic_table_connector ON connector.dynamic_table(connector_id);
