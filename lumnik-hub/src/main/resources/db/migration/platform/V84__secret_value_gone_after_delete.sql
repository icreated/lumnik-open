-- V84 (#1569) — A deleted secret stops holding its value.
--
-- `lm secret rm` was a SOFT delete: SecretResource.delete stamped deleted_at and the row stayed,
-- value_encrypted included. The name was free again (V44) and no reader saw the row, but whoever
-- holds the master key could still decrypt a secret an admin had removed. The delete door now
-- removes the row; this removes the tombstones earlier versions left behind, in every tenant
-- (Flyway runs on the admin datasource, which bypasses RLS). The audit trail keeps each deletion:
-- it never stored the value.
--
-- The deleted_at column stays, with the partial indexes and the readers' deleted_at IS NULL filter:
-- the shared entity superclass maps it, and with no tombstone left the filter keeps every row.

DELETE FROM platform.secret WHERE deleted_at IS NOT NULL;
