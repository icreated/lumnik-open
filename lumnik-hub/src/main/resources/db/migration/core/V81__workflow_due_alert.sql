-- V81 — the memory of a watched due date (#1448): one row per document and deadline it was alerted for.
-- The outbox is the transport, not the memory: EventRetentionPurger deletes events after the retention, and a
-- document still overdue would be alerted again on the next sweep. A due date that moves to another passed date
-- is a new deadline and a new row; one that moves back to a deadline already alerted finds its row and stays quiet.
-- due_at is the instant the deadline passed: the end of the day in spec.due.zone for a date column.
CREATE TABLE core.workflow_due_alert (
    tenant_id      BIGINT       NOT NULL,
    workflow_name  TEXT         NOT NULL,
    doc_key        TEXT         NOT NULL,
    due_at         TIMESTAMPTZ  NOT NULL,
    alerted_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_workflow_due_alert PRIMARY KEY (tenant_id, workflow_name, doc_key, due_at)
);
ALTER TABLE core.workflow_due_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY workflow_due_alert_tenant_isolation ON core.workflow_due_alert
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
