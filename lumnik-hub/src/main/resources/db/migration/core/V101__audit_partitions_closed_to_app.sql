-- V101 — The request-path roles reach the audit trail through core.audit_event only (#2089).
--
-- Row-level security and the tenant policy are on the parent. A query that names a partition is
-- checked against the partition's own RLS, which is off, so lumnik_app could read every tenant's
-- audit rows from core.audit_event_default, and insert there past the policy's check. No code
-- names the partition: rows enter through the parent, on the admin datasource, and lumnik_app
-- reads through the parent. A query through the parent checks the parent's privileges only.
--
-- So every partition is closed to lumnik_app and lumnik_readonly. A future partition-creating
-- migration revokes the same in the same statement. Guarded on each role's existence, as V47:
-- the shared DevServices container has no such roles.
DO $$
DECLARE
  part regclass;
  r text;
BEGIN
  FOR part IN
    SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'core.audit_event'::regclass
  LOOP
    FOREACH r IN ARRAY ARRAY['lumnik_app', 'lumnik_readonly'] LOOP
      IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
        EXECUTE format('REVOKE ALL ON %s FROM %I', part, r);
      END IF;
    END LOOP;
  END LOOP;
END $$;
