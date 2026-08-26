-- Replace is_active (boolean flag) with archived_at (timestamp).
-- NULL = active/listable; SET = archived, hidden from pickers.
-- Applies to entities backed by AuditedEntity: tenant, org, app_role, app_user, session.
-- user_role and role_org_access retain their own is_active (not AuditedEntity subclasses).

ALTER TABLE identity.tenant     ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;
ALTER TABLE identity.org        ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;
ALTER TABLE identity.app_role   ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;
ALTER TABLE identity.app_user   ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;
ALTER TABLE identity.session    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;

-- Migrate: inactive records get an archived timestamp
UPDATE identity.tenant   SET archived_at = NOW() WHERE is_active = FALSE;
UPDATE identity.org      SET archived_at = NOW() WHERE is_active = FALSE;
UPDATE identity.app_role SET archived_at = NOW() WHERE is_active = FALSE;
UPDATE identity.app_user SET archived_at = NOW() WHERE is_active = FALSE;
UPDATE identity.session  SET archived_at = NOW() WHERE is_active = FALSE;

-- Drop the old boolean flag
ALTER TABLE identity.tenant   DROP COLUMN IF EXISTS is_active;
ALTER TABLE identity.org      DROP COLUMN IF EXISTS is_active;
ALTER TABLE identity.app_role DROP COLUMN IF EXISTS is_active;
ALTER TABLE identity.app_user DROP COLUMN IF EXISTS is_active;
ALTER TABLE identity.session  DROP COLUMN IF EXISTS is_active;
