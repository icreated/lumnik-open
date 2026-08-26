-- Session table was created before AuditedEntity added is_active.
-- All existing sessions are considered active by default.
ALTER TABLE identity.session ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
