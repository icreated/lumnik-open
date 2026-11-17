-- V95 (#2015) — A secret carries scopes.
--
-- A secret was the tenant's, with no scope: a connector of any scope resolved any secret of its
-- tenant by name. A scope-bound integrator may write a connector in its own scopes, so it could name
-- the tenant's ERP_PASSWORD in one, point base_url at a host of its own and receive the password.
--
-- A connector (or an export) now reads a secret only when it carries every scope the secret does:
-- PgcryptoSecretResolver compares this column with the reader's scopes before it decrypts anything.
-- Empty, as every existing secret is, serves the scopeless connectors only. `lm secret scope NAME
-- SCOPE,SCOPE` sets it.

ALTER TABLE platform.secret ADD COLUMN scopes TEXT[] NOT NULL DEFAULT '{}';
