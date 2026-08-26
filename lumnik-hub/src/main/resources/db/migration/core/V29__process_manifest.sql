-- V29: kind:Process (notify) manifests — per-tenant, RLS from day one.
-- Spec: docs/superpowers/specs/2026-07-05-kind-process-notify-design.md.
-- Deliberate divergence from connector.entity_manifest (global, no RLS): a process RUNS
-- server-side against tenant data; its declaration is tenant state.
CREATE TABLE core.process_manifest (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   BIGINT       NOT NULL,
    name        TEXT         NOT NULL,
    yaml_text   TEXT         NOT NULL,
    scope       TEXT         NOT NULL,
    on_table    TEXT         NOT NULL,
    emit_type   TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_process_manifest PRIMARY KEY (id),
    CONSTRAINT uq_process_manifest_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_process_manifest_gate ON core.process_manifest (tenant_id, on_table) WHERE enabled;
ALTER TABLE core.process_manifest ENABLE ROW LEVEL SECURITY;
CREATE POLICY process_manifest_tenant_isolation ON core.process_manifest
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.process_manifest IS
    'kind:Process declarations (notify rung): on data.row.changed(on_table) when <conditions> emit <emit_type>.';
