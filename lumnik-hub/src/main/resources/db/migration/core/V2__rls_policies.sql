-- Row-Level Security for core tenanted tables.
-- Policy reads current_setting('app.current_tenant', true) set by TenantConnectionInterceptor.
-- lumnik_admin (BYPASSRLS) is not affected.

ALTER TABLE core.installed_module    ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.tenant_setting      ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.tenant_customization ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.audit_outbox        ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.audit_event         ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.event_outbox        ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON core.installed_module
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON core.tenant_setting
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON core.tenant_customization
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON core.audit_outbox
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON core.audit_event
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON core.event_outbox
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
