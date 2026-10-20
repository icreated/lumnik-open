-- V74 — A run counts the rows it removed (#1195).
-- A csv-file connector reading one local file holds the source's state: a run that read the whole
-- file removes the rows the file no longer has. That count lives beside the conservation identity
-- (records_in = records_out + records_skipped), not inside it: a removed row was never read by the
-- run that removed it. Metadata-only (a constant default), no rewrite.

ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS records_removed BIGINT NOT NULL DEFAULT 0;
