-- lumnik Identity & Tenancy — V1
-- Tenant and Organization aggregates

-- ─────────────────────────────────────────────────────
-- TENANT  (iDempiere: AD_Client)
-- ─────────────────────────────────────────────────────
CREATE SEQUENCE tenant_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE tenant (
    id          BIGINT       NOT NULL DEFAULT nextval('tenant_id_seq'),
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(255),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_tenant      PRIMARY KEY (id),
    CONSTRAINT uq_tenant_code UNIQUE (code)
);

COMMENT ON TABLE  tenant IS 'Top-level isolation boundary. Equivalent to iDempiere AD_Client.';
COMMENT ON COLUMN tenant.code IS 'Short mnemonic key (max 40 chars). Unique across all tenants.';

-- ─────────────────────────────────────────────────────
-- ORGANIZATION  (iDempiere: AD_Org)
-- ─────────────────────────────────────────────────────
CREATE SEQUENCE org_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE org (
    id            BIGINT      NOT NULL DEFAULT nextval('org_id_seq'),
    tenant_id     BIGINT      NOT NULL,
    org_id        BIGINT      NOT NULL DEFAULT 0,
    code          VARCHAR(40) NOT NULL,
    name          VARCHAR(60) NOT NULL,
    description   VARCHAR(255),
    parent_org_id BIGINT,
    is_summary    BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL,
    created_by    BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL,
    updated_by    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_org            PRIMARY KEY (id),
    CONSTRAINT fk_org_tenant     FOREIGN KEY (tenant_id)     REFERENCES tenant(id),
    CONSTRAINT fk_org_parent     FOREIGN KEY (parent_org_id) REFERENCES org(id),
    CONSTRAINT uq_org_code       UNIQUE (tenant_id, code),
    CONSTRAINT chk_org_no_self   CHECK (id != parent_org_id)
);

COMMENT ON TABLE  org IS 'Organization unit within a tenant. Equivalent to iDempiere AD_Org.';
COMMENT ON COLUMN org.is_summary IS 'Summary orgs are hierarchy nodes only — no transactions allowed.';
