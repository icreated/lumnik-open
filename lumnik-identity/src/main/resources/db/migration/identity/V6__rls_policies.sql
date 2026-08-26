-- Row-Level Security for identity tenanted tables.
-- Policy reads current_setting('app.current_tenant', true) set by TenantConnectionInterceptor.
-- lumnik_admin (BYPASSRLS) is not affected.
-- identity.tenant is intentionally excluded — it IS the tenant table.

ALTER TABLE identity.org              ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.app_role         ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.app_user         ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.user_role        ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.session          ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.role_org_access  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON identity.org
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON identity.app_role
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON identity.app_user
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON identity.user_role
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON identity.session
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

CREATE POLICY tenant_isolation ON identity.role_org_access
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
