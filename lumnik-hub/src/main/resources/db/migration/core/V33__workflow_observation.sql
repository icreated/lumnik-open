-- V33: the tape recorder — workflow dormancy's observation ledger + transition journal.
-- Spec: docs/superpowers/specs/2026-08-04-workflow-dormancy-design.md.
-- workflow_transition ships WITHOUT a reader, deliberately: it is the tape that step 2 of
-- the time-defrost staircase (observed transition baselines — the implicit SLA) computes on.
-- Ratified as the compounding asset; do not remove it as dead weight.
CREATE TABLE core.workflow_observation (
    tenant_id          BIGINT       NOT NULL,
    workflow_name      TEXT         NOT NULL,
    doc_key            TEXT         NOT NULL,
    state              TEXT         NOT NULL,
    since              TIMESTAMPTZ  NOT NULL,
    first_observed_at  TIMESTAMPTZ  NOT NULL,
    alerted_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_workflow_observation PRIMARY KEY (tenant_id, workflow_name, doc_key)
);
ALTER TABLE core.workflow_observation ENABLE ROW LEVEL SECURITY;
CREATE POLICY workflow_observation_tenant_isolation ON core.workflow_observation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

CREATE TABLE core.workflow_transition (
    id             BIGSERIAL    NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    workflow_name  TEXT         NOT NULL,
    doc_key        TEXT         NOT NULL,
    from_state     TEXT         NOT NULL,
    to_state       TEXT         NOT NULL,
    observed_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_workflow_transition PRIMARY KEY (id)
);
CREATE INDEX idx_workflow_transition_doc
    ON core.workflow_transition (tenant_id, workflow_name, doc_key, observed_at);
ALTER TABLE core.workflow_transition ENABLE ROW LEVEL SECURITY;
CREATE POLICY workflow_transition_tenant_isolation ON core.workflow_transition
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

COMMENT ON TABLE core.workflow_observation IS
    'Current observed state per (workflow, document): since = when WE started observing this state. Observation-relative, never source-absolute.';
COMMENT ON TABLE core.workflow_transition IS
    'Append-only observed state changes — the tape. No reader in v1 by design (see spec).';
