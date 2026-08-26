-- V14__drop_local_credentials.sql — the placeholder local-password path is gone.
-- Authentication is Keycloak/OIDC only: users are JIT-provisioned on (idp_issuer, idp_subject),
-- so lumnik never stores a credential. Drop the columns that only that path ever wrote.
ALTER TABLE identity.app_user
    DROP CONSTRAINT IF EXISTS chk_user_failed;

ALTER TABLE identity.app_user
    DROP COLUMN IF EXISTS password_hash,
    DROP COLUMN IF EXISTS password_expires_at,
    DROP COLUMN IF EXISTS failed_login_count;

-- is_locked survives (SCIM/JIT read it via isLoginActive), but it is now set by an
-- administrator, not by a failed-attempt counter.
COMMENT ON COLUMN identity.app_user.is_locked IS 'Administratively locked. Blocks login even with a valid IdP token.';
