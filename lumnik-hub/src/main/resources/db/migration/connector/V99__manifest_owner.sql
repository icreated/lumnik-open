-- V99 — An Entity, View or App manifest belongs to the tenant it was applied from (#2050).
-- Their names stay unique on the hub, but only the owner reads one. NULL is shared: every tenant reads it. That is a
-- manifest applied from the hub's own tenant (hubadmin, 11), and a built-in app.
ALTER TABLE connector.entity_manifest  ADD COLUMN IF NOT EXISTS owner_tenant_id BIGINT;
ALTER TABLE connector.saved_view       ADD COLUMN IF NOT EXISTS owner_tenant_id BIGINT;
ALTER TABLE connector.app_registration ADD COLUMN IF NOT EXISTS owner_tenant_id BIGINT;

-- One default app per owner: a tenant's own, and the shared one every tenant without its own falls back on.
DROP INDEX IF EXISTS connector.app_registration_one_default;
CREATE UNIQUE INDEX app_registration_one_default
    ON connector.app_registration ((COALESCE(owner_tenant_id, 0))) WHERE is_default;

-- What was written before: on a hub with one customer tenant (besides hubadmin, archived ones counted, as the hub
-- counts them for #1991), that tenant wrote it, and keeps changing it: it becomes its own. With none or several, it
-- stays shared, as it was read. The built-in apps, as V48 seeded them, stay shared whatever the count. A default app
-- the tenant made becomes its own default, and a built-in becomes the shared default: there must always be one.
DO $$
DECLARE
    only_tenant BIGINT;
    holder      TEXT;
    fallback    TEXT;
BEGIN
    IF to_regclass('identity.tenant') IS NULL THEN
        RETURN;   -- a fresh install whose identity schema comes later: nothing applied yet
    END IF;
    SELECT min(id) INTO only_tenant FROM identity.tenant WHERE id <> 11 HAVING count(*) = 1;
    IF only_tenant IS NULL THEN
        RETURN;
    END IF;
    UPDATE connector.entity_manifest SET owner_tenant_id = only_tenant WHERE owner_tenant_id IS NULL;
    UPDATE connector.saved_view SET owner_tenant_id = only_tenant WHERE owner_tenant_id IS NULL;
    UPDATE connector.app_registration SET owner_tenant_id = only_tenant
     WHERE owner_tenant_id IS NULL AND yaml_text NOT LIKE '# seeded at V48%' AND NOT is_default;

    SELECT name INTO holder FROM connector.app_registration
     WHERE is_default AND owner_tenant_id IS NULL AND yaml_text NOT LIKE '# seeded at V48%';
    SELECT name INTO fallback FROM connector.app_registration
     WHERE owner_tenant_id IS NULL AND yaml_text LIKE '# seeded at V48%' ORDER BY name LIMIT 1;
    IF holder IS NOT NULL AND fallback IS NOT NULL THEN
        UPDATE connector.app_registration SET owner_tenant_id = only_tenant WHERE name = holder;
        UPDATE connector.app_registration SET is_default = true WHERE name = fallback;
    END IF;
END $$;
