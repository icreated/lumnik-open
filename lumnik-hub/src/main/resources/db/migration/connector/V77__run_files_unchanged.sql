-- V77 — A run counts the files it did not read because they had not changed (#1371).
-- A csv run over a file already read to its end reads nothing when the file is unchanged; IN 0 alone
-- cannot tell that from an empty delta. Metadata-only (a constant default), no rewrite.

ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS files_unchanged BIGINT NOT NULL DEFAULT 0;
