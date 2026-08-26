-- Per-métier scopes that make a fused entity discoverable to the ask-to-SQL chat.
-- Empty = not discoverable (explicit opt-in). Array, mirroring connector.connector.tags.
ALTER TABLE connector.entity_manifest ADD COLUMN scopes TEXT[] NOT NULL DEFAULT '{}';
