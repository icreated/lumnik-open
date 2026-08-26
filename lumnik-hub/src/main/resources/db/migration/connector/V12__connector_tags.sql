-- V12__connector_tags.sql — orthogonal semantic tagging for RAG scope
ALTER TABLE connector.connector
    ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX idx_connector_tags ON connector.connector USING GIN (tags)
    WHERE deleted_at IS NULL;
