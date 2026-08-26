#!/bin/bash
# Hub database roles — env-driven passwords (P8 on the pre-flip register).
# Runs once at first initdb (docker-entrypoint-initdb.d), superuser context.
# The deploy paths (selfhost up.sh, helm up.sh) always inject per-install
# generated LUMNIK_*_DB_PASSWORD values; the dev fallbacks below exist only so a
# bare `docker run` for local hacking still boots. Values are openssl base64
# (A-Za-z0-9+/=) — no single quotes, safe to inline in SQL string literals.
set -euo pipefail

APP_PW="${LUMNIK_APP_DB_PASSWORD:-lumnik_app_dev_password}"
ADMIN_PW="${LUMNIK_ADMIN_DB_PASSWORD:-lumnik_admin_dev_password}"
RO_PW="${LUMNIK_READONLY_DB_PASSWORD:-lumnik_readonly_dev_password}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_admin') THEN
        CREATE ROLE lumnik_admin
            WITH LOGIN
            PASSWORD '${ADMIN_PW}'
            BYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
        CREATE ROLE lumnik_app
            WITH LOGIN
            PASSWORD '${APP_PW}';
    END IF;
END \$\$;

GRANT CONNECT ON DATABASE lumnik TO lumnik_admin, lumnik_app;

-- Set default privileges so every table/sequence Flyway creates
-- (as lumnik_admin) is automatically accessible to lumnik_app.
-- This must be run by a superuser on behalf of lumnik_admin.
ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lumnik_app;
ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO lumnik_app;

-- Read-only role: SELECT-only, the ask-to-SQL SqlGuard physical firewall (spec §4).
-- Created HERE (superuser context) — not in Flyway V15 — because the migration user
-- lumnik_admin intentionally lacks CREATEROLE. V15 only adds the GRANTs.
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
        CREATE ROLE lumnik_readonly
            WITH LOGIN
            PASSWORD '${RO_PW}';
    END IF;
END \$\$;
ALTER ROLE lumnik_readonly SET default_transaction_read_only = on;
GRANT CONNECT ON DATABASE lumnik TO lumnik_readonly;
SQL
