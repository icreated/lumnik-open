-- P9: the retention purge cuts on occurred_at daily; without this the DELETE seq-scans the
-- (single, DEFAULT) partition. On the partitioned parent: cascades to every partition.
CREATE INDEX IF NOT EXISTS idx_audit_event_occurred_at ON core.audit_event (occurred_at);
