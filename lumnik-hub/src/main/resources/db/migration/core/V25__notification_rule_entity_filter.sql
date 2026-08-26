-- notification_rule gains the same entity scope webhook_endpoint already has (V24):
-- a data.row.changed rule can restrict itself to a single hub table. NULL = all tables
-- (existing rows keep their current broad behavior — backward-compatible, no backfill).
ALTER TABLE core.notification_rule ADD COLUMN entity_filter VARCHAR(120);
COMMENT ON COLUMN core.notification_rule.entity_filter IS
    'Optional: restrict data.row.changed to a single hub table (É2.b gate).';
