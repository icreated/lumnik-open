-- V21: Defensive NULLIF guard on every tenant RLS policy.
--
-- Every tenant-isolation policy casts the session GUC directly:
--   tenant_id = current_setting('app.current_tenant', true)::bigint
-- If that GUC is ever set to an EMPTY STRING ('') — as happened on the live hub
-- during an async connector run — the cast ''::bigint throws PostgreSQL error
-- 22P02 (invalid input syntax for type bigint: ""), which surfaced as a connector
-- run that silently no-ops (the RLS-protected query blew up and the error was swallowed).
--
-- This migration makes every policy empty-safe by wrapping the cast in NULLIF(...,''):
--   tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint
-- An empty GUC now yields NULL, the predicate is false, and the query degrades to
-- "no rows" instead of crashing.
--
-- Flyway topology note: lumnik-platform runs core + connector + platform under ONE
-- shared Flyway history (schema 'core', see module-manifest.yaml — three locations,
-- single schema). Version numbers are therefore a single contiguous sequence across
-- all three dirs; V21 is the next free version overall. ALTER POLICY statements use
-- fully-qualified table names so this single migration can touch all three schemas.
--
-- Every ALTER runs after its corresponding CREATE POLICY (earlier versions in the
-- shared history), so the policies always exist by the time this runs.

-- ── core schema ───────────────────────────────────────────────────────────────
ALTER POLICY tenant_isolation ON core.installed_module
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON core.tenant_setting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON core.tenant_customization
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON core.audit_outbox
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON core.audit_event
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- event_outbox keeps its '0' system-mode sentinel branch (V4); only the cast is wrapped.
ALTER POLICY tenant_isolation ON core.event_outbox
    USING (
        current_setting('app.current_tenant', true) = '0'
        OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint
    );

ALTER POLICY webhook_endpoint_tenant_isolation ON core.webhook_endpoint
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY webhook_delivery_tenant_isolation ON core.webhook_delivery
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY hook_tenant_override_isolation ON core.hook_tenant_override
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY list_definition_isolation ON core.list_definition
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY list_value_isolation ON core.list_value
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- ── connector schema ────────────────────────────────────────────────────────────
ALTER POLICY tenant_isolation ON connector.connector
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_cursor
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_run
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_mapping
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_credential
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_dead_letter
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY dynamic_table_tenant_isolation ON connector.dynamic_table
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.connector_endpoint
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY tenant_isolation ON connector.cursor_state
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

ALTER POLICY schema_card_tenant_isolation ON connector.schema_card
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

-- ── platform schema ──────────────────────────────────────────────────────────────
ALTER POLICY tenant_isolation ON platform.secret
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
