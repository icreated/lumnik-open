-- V86 — the undeclared values a workflow has already reported (#1589): the memory behind spec.check.trigger: ingestion.
-- An apply rewrites a workflow's rows with its report, which the integrator has just read. After each completed
-- run on the watched table, the check inserts what it finds; only a value that was not there yet is news, and
-- only news becomes a workflow.undeclared.<workflow> event. The outbox is the transport, not the memory: it is
-- purged after the retention, and a value still undeclared would otherwise be said again.
CREATE TABLE core.workflow_undeclared_value (
    tenant_id      BIGINT       NOT NULL,
    workflow_name  TEXT         NOT NULL,
    value          TEXT         NOT NULL,
    reported_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_workflow_undeclared_value PRIMARY KEY (tenant_id, workflow_name, value)
);
ALTER TABLE core.workflow_undeclared_value ENABLE ROW LEVEL SECURITY;
CREATE POLICY workflow_undeclared_value_tenant_isolation ON core.workflow_undeclared_value
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
