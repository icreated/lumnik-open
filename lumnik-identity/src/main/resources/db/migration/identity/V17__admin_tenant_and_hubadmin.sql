-- V17 — seed the shipped realm's hubadmin, and the tenant it claims.
--
-- V13 said "the shipped realm's users all claim tenant_id=1". Never true of
-- 'hubadmin', which has claimed tenant_id=11 since before V13 — so on a
-- self-host install its first login died on fk_user_tenant (closed edition,
-- JIT) or was refused outright (open edition, no user_id claim). The realm
-- now gives it user_id=2; the product ships the tenant and the row.
--
-- Same contract as V13/V16: ids below the sequence floors, idempotency on
-- (id) only, the user row checked after the insert. Two things are V17's:
--   * the TENANT is NOT checked after the insert — the realm names it by id
--     alone, so any live tenant 11 is the one it means, including one the
--     operator created first;
--   * its code is 'hubadmin', not 'admin' — an operator may already have a
--     tenant named 'admin', and a seed must not turn that into an upgrade
--     failure on uq_tenant_code.
-- ShippedRealmUserSeedIT holds realm and seeds together, on both axes.
INSERT INTO tenant (id, code, name, description, created_at, updated_at)
VALUES (11, 'hubadmin', 'Hub Admin Tenant',
        'Seeded at install — the tenant the bundled realm''s hubadmin claims (tenant_id=11).',
        now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, tenant_id, login, name, email, description, created_at, updated_at)
VALUES (2, 11, 'hubadmin', 'Hub Admin', 'hubadmin@lumnik.test',
        'Seeded at install — the user the bundled realm''s hubadmin claims (user_id=2).',
        now(), now())
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = 2 AND tenant_id = 11 AND login = 'hubadmin') THEN
    RAISE EXCEPTION 'identity.app_user id 2 is not tenant 11 / login hubadmin — the shipped realm binds user_id=2 to that row; resolve it before starting the hub';
  END IF;
END $$;
