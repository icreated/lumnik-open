-- V22: System-mode sentinel on the connector schema (the V4 pattern, extended).
-- TenantConnectionInterceptor now sets app.current_tenant='0' in system mode (it used to SKIP the
-- GUC, leaving the predicate NULL -> zero rows / rejected writes on an RLS-enforced hub — finding
-- lm-connector-run-rls-404). '0' is the established system sentinel (V4 event_outbox, RAG corpus).
-- Scope: connector schema ONLY — identity/rag stay strict (design decision D4).

ALTER POLICY tenant_isolation ON connector.connector
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_cursor
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_run
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_mapping
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_credential
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_dead_letter
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.connector_endpoint
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY tenant_isolation ON connector.cursor_state
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY dynamic_table_tenant_isolation ON connector.dynamic_table
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
ALTER POLICY schema_card_tenant_isolation ON connector.schema_card
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
