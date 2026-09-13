-- core.audit_event is append-only for the request-path role.
--
-- 01-roles.sh grants lumnik_app SELECT, INSERT, UPDATE, DELETE on every table lumnik_admin
-- creates, so the audit trail could be edited or erased by any code path (or any bug) running on
-- the default datasource. Nothing in the hub ever does: rows enter through AuditDispatcher and
-- leave through AuditRetentionPurger, both on the admin datasource. The grant said otherwise, and
-- an auditor reads grants, not intentions.
--
-- Revoked on the parent AND on every partition: PostgreSQL checks the parent's privileges for a
-- query through the parent, and the partition's own for a direct query on it. Partitions are
-- created by migration only (V1: audit_event_default), so this DO block covers every one that
-- exists; a future partition-creating migration revokes the same in the same statement.
-- Guarded on the role's existence, as V15 does for lumnik_readonly: the shared DevServices
-- container has no such role.
DO $$
DECLARE
  part regclass;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    REVOKE UPDATE, DELETE, TRUNCATE ON core.audit_event FROM lumnik_app;
    FOR part IN
      SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'core.audit_event'::regclass
    LOOP
      EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM lumnik_app', part);
    END LOOP;
  END IF;
END $$;
