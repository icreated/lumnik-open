-- V32: kind:Workflow manifests — per-tenant, RLS from day one.
-- Spec: docs/superpowers/specs/2026-08-04-kind-workflow-design.md.
-- Mirrors V29 process_manifest: a workflow describes tenant data; its declaration is tenant state.
CREATE TABLE core.workflow_manifest (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   BIGINT       NOT NULL,
    name        TEXT         NOT NULL,
    yaml_text   TEXT         NOT NULL,
    scope       TEXT         NOT NULL,
    on_table    TEXT         NOT NULL,
    on_column   TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_workflow_manifest PRIMARY KEY (id),
    CONSTRAINT uq_workflow_manifest_tenant_name UNIQUE (tenant_id, name),
    -- A column has exactly one lifecycle (WorkflowApplyResource's findOtherOnColumn pre-check).
    -- That pre-check does not filter by `enabled` — there is no path that ever sets it false,
    -- so a plain UNIQUE agrees with it exactly rather than inventing a partial-index policy
    -- (WHERE enabled) for a state the app cannot yet reach. This is also the guard the
    -- pre-check alone cannot give under READ COMMITTED: it turns a race between two concurrent
    -- applies of different names into a DB-enforced 23505 instead of two silently-coexisting rows.
    CONSTRAINT uq_workflow_manifest_tenant_column UNIQUE (tenant_id, on_table, on_column)
);
ALTER TABLE core.workflow_manifest ENABLE ROW LEVEL SECURITY;
CREATE POLICY workflow_manifest_tenant_isolation ON core.workflow_manifest
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.workflow_manifest IS
    'kind:Workflow declarations: the document lifecycle of on_table.on_column — states, aliases, transitions, provenance.';
