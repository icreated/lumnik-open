-- Make a fresh DB ready for the hub, on first init (as the bootstrap superuser).
-- Runs after 01-roles.sql.

-- PlatformFlywayRunner runs migrations as lumnik_admin, which must CREATE SCHEMA;
-- the rag schema's V1 needs the pgvector extension (superuser-only to create).
GRANT CREATE, CONNECT, TEMP ON DATABASE lumnik TO lumnik_admin;
CREATE EXTENSION IF NOT EXISTS vector;

-- Pre-create the app schemas owned by the migration role so Flyway's
-- CREATE SCHEMA IF NOT EXISTS is a no-op, then grant the non-owner runtime roles
-- USAGE. Schema USAGE is NOT covered by ALTER DEFAULT PRIVILEGES (01-roles.sql),
-- so without this the app role hits 'permission denied for schema core' at runtime.
CREATE SCHEMA IF NOT EXISTS core      AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS identity  AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS connector AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS platform  AUTHORIZATION lumnik_admin;
CREATE SCHEMA IF NOT EXISTS rag       AUTHORIZATION lumnik_admin;
GRANT USAGE ON SCHEMA core, identity, connector, platform, rag TO lumnik_app, lumnik_readonly;
