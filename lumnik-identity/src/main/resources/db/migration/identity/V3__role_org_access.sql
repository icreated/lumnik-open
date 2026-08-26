-- lumnik Identity & Tenancy — V3
-- RoleOrgAccess: explicit org grants for manual roles (iDempiere: AD_RoleOrgAccess)

CREATE TABLE role_org_access (
    role_id      BIGINT      NOT NULL,
    org_id       BIGINT      NOT NULL,
    tenant_id    BIGINT      NOT NULL,
    is_read_only BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_role_org_access PRIMARY KEY (role_id, org_id),
    CONSTRAINT fk_roa_role        FOREIGN KEY (role_id)   REFERENCES app_role(id),
    CONSTRAINT fk_roa_org         FOREIGN KEY (org_id)    REFERENCES org(id),
    CONSTRAINT fk_roa_tenant      FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

COMMENT ON TABLE  role_org_access IS 'Explicit org grants for manual roles. Equivalent to iDempiere AD_RoleOrgAccess.';
COMMENT ON COLUMN role_org_access.is_read_only IS 'Read but not write in this org.';
COMMENT ON COLUMN role_org_access.is_active    IS 'Soft-delete.';
