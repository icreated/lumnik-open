-- V79 — A run counts the files it read and could not archive (#1382).
-- after_process move/delete failing on a file (an archive folder it cannot write, say) left the file in
-- the source folder with a WARN in the hub's log only. Metadata-only (a constant default), no rewrite.

ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS files_not_archived BIGINT NOT NULL DEFAULT 0;
