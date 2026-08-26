-- lumnik Platform — V7 Reference lists (CRUD valeurs métier)
-- Value/Name pattern: value is immutable technical key, name is editable display label.

CREATE SEQUENCE core.list_definition_id_seq START 1000;
CREATE SEQUENCE core.list_value_id_seq      START 1000;

CREATE TABLE core.list_definition (
    id            BIGINT       NOT NULL DEFAULT nextval('core.list_definition_id_seq'),
    tenant_id     BIGINT       NOT NULL,
    org_id        BIGINT       NOT NULL DEFAULT 0,
    code          VARCHAR(80)  NOT NULL,
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    system_owned  BOOLEAN      NOT NULL DEFAULT false,
    archived_at   TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_list_definition PRIMARY KEY (id),
    CONSTRAINT uq_list_definition_code UNIQUE (tenant_id, code)
);

CREATE TABLE core.list_value (
    id            BIGINT       NOT NULL DEFAULT nextval('core.list_value_id_seq'),
    list_id       BIGINT       NOT NULL REFERENCES core.list_definition(id),
    value         VARCHAR(80)  NOT NULL,
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    color         VARCHAR(16),
    sort_order    INT          NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    tenant_id     BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_list_value PRIMARY KEY (id),
    CONSTRAINT uq_list_value UNIQUE (list_id, value)
);

CREATE INDEX ix_list_value_list ON core.list_value (list_id, sort_order);

ALTER TABLE core.list_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY list_definition_isolation ON core.list_definition
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

ALTER TABLE core.list_value ENABLE ROW LEVEL SECURITY;
CREATE POLICY list_value_isolation ON core.list_value
    USING (tenant_id = current_setting('app.current_tenant', true)::bigint);

COMMENT ON TABLE  core.list_definition IS 'Tenant-scoped business reference lists.';
COMMENT ON COLUMN core.list_definition.code IS 'Stable technical key. Immutable. Used by code refs.';
COMMENT ON COLUMN core.list_definition.name IS 'Display label. Editable.';
COMMENT ON COLUMN core.list_definition.system_owned IS
    'true = list mirrors a Java enum via @ListMirror (read-only structure, editable labels).';
COMMENT ON TABLE  core.list_value IS 'Values for a list. Value/Name pattern.';
COMMENT ON COLUMN core.list_value.value IS 'Stable technical code. Immutable per spec.';
