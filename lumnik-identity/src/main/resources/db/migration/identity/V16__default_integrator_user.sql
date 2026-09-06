-- V16 — seed the shipped realm's integrator.
--
-- V13 seeded the tenant the bundled realm's users claim and stopped one row
-- short: the same realm gives 'integrator' the attribute user_id=1, and the
-- filter binds that claim WITHOUT requiring a row ("the hub does not own
-- every IdP's user table"). Doors that only attribute an action work; doors
-- that load the user answer as if nobody were there (users/me/scope → 404).
-- Found live 2026-09-04 by `lm scope set`. ShippedRealmUserSeedIT holds the
-- realm and this seed together.
--
-- The explicit id 1 is safe: app_user_id_seq starts at 1000 (V2). Same
-- idempotency contract as V13 — (id) ONLY: a foreign row holding login
-- 'integrator' in tenant 1 fails loudly on uq_user_login. And the case that
-- contract leaves open — id 1 already held by SOME OTHER row, which only a
-- manual insert below the sequence floor can produce — is checked after the
-- insert: the realm will bind user_id=1 to whatever row 1 is, so a wrong one
-- must stop the hub, never skip silently. No idp_issuer/idp_subject: this
-- row is reached by the claim, not by JIT, and stays inert if the mapper goes.
INSERT INTO app_user (id, tenant_id, login, name, email, description, created_at, updated_at)
VALUES (1, 1, 'integrator', 'Integrator', 'integrator@lumnik.test',
        'Seeded at install — the user the bundled realm''s integrator claims (user_id=1).',
        now(), now())
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = 1 AND tenant_id = 1 AND login = 'integrator') THEN
    RAISE EXCEPTION 'identity.app_user id 1 is not tenant 1 / login integrator — the shipped realm binds user_id=1 to that row; resolve it before starting the hub';
  END IF;
END $$;
