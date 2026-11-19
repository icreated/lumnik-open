#!/bin/bash
# Keycloak's own database and role (#1997): Keycloak keeps its realms, users and keys here, apart from the hub's
# data. Runs at first initdb (docker-entrypoint-initdb.d), and in the Helm installer's bootstrap Job of an external
# PostgreSQL. The self-host up.sh also runs it on every start, so an older volume gets them and a new
# KEYCLOAK_DB_PASSWORD in .env reaches the role; on Helm, a new password reaches the role only when this script runs
# again (docs/operations/upgrade.md). Idempotent. The password is openssl base64 (no quote to escape) and rides psql's
# stdin, never argv.
# Without KEYCLOAK_DB_PASSWORD it does nothing: the dev compose and a chart without Keycloak have no use for it.
# No `exit` and no `set`: the image's entrypoint SOURCES a script it cannot execute (the chart mounts it 0644).
if [ -n "${KEYCLOAK_DB_PASSWORD:-}" ]; then
psql -q -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-lumnik}" <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
        CREATE ROLE keycloak WITH LOGIN;
    END IF;
END \$\$;
ALTER ROLE keycloak PASSWORD '${KEYCLOAK_DB_PASSWORD}';
SELECT 'CREATE DATABASE keycloak OWNER keycloak' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
-- Keycloak's role alone opens it: the hub's roles have no business with password hashes and signing keys.
REVOKE CONNECT, TEMPORARY ON DATABASE keycloak FROM PUBLIC;
SQL
fi
