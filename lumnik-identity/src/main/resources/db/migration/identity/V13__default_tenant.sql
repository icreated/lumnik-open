-- V13 — seed the default tenant.
--
-- A fresh install has ZERO tenants, yet the shipped realm's users all claim
-- tenant_id=1 — so the very first external login walks JIT provisioning
-- (JitUserProvisioner) and dies on fk_user_tenant. Found live by the mobile
-- spike (2026-07-11): selfhost never seeded identity.tenant; lm-cli tokens
-- masked the gap because their user_id claim short-circuits JIT.
--
-- The explicit id 1 is safe: tenant_id_seq starts at 1000 (V1), so API-created
-- tenants can never collide with the seed. Idempotency targets (id) ONLY: if
-- tenant 1 already exists the contract is satisfied and the skip is correct —
-- but if some OTHER tenant already holds code 'default', the insert must fail
-- loudly on uq_tenant_code (operator renames it), never skip silently and
-- leave id=1 missing.
INSERT INTO tenant (id, code, name, description, created_at, updated_at)
VALUES (1, 'default', 'Default Tenant',
        'Seeded at install — the tenant the bundled realm''s users claim (tenant_id=1).',
        now(), now())
ON CONFLICT (id) DO NOTHING;
