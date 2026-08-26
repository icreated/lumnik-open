-- lumnik Platform — V6 Per-tenant hook overrides
-- Allows a tenant admin to disable a hook for their tenant without affecting other tenants.

CREATE TABLE core.hook_tenant_override (
    tenant_id   BIGINT       NOT NULL,
    hook_id     VARCHAR(120) NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_hook_tenant_override PRIMARY KEY (tenant_id, hook_id)
);

ALTER TABLE core.hook_tenant_override ENABLE ROW LEVEL SECURITY;
CREATE POLICY hook_tenant_override_isolation ON core.hook_tenant_override
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

COMMENT ON TABLE core.hook_tenant_override IS
    'Per-tenant on/off switch for a registered hook. Absence of a row = use the hook''s default enabled flag.';
