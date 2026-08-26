-- Inbound SCIM auth: a hashed per-tenant opaque bearer token. The token plaintext is shown
-- once at mint and never stored; only its SHA-256 hex lives here. issuer = the IdP `iss` this
-- token provisions for (so SCIM-create and JIT-login agree on (idp_issuer, idp_subject)).
CREATE SEQUENCE scim_token_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE identity.scim_token (
    id          bigint      NOT NULL DEFAULT nextval('scim_token_id_seq') PRIMARY KEY,
    tenant_id   bigint      NOT NULL REFERENCES identity.tenant(id),
    org_id      bigint      NOT NULL DEFAULT 0,
    issuer      varchar(255) NOT NULL,
    token_hash  varchar(64) NOT NULL,
    label       varchar(120),
    revoked_at  timestamptz,
    archived_at timestamptz,
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  bigint      NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  bigint      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_scim_token_hash ON identity.scim_token (token_hash);

-- RLS: a tenant admin sees only its own tokens. The pre-tenant auth lookup uses the
-- BYPASSRLS owner role (lumnik_admin), so resolution works before the tenant is known.
ALTER TABLE identity.scim_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON identity.scim_token
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
