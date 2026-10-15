-- Entity resolution (#613/#615): tenant-safe crosswalk, fingerprint, assertion and evidence
-- storage. Spec: docs/superpowers/specs/2026-09-20-entity-resolution-design.md §3.
--
-- Per-tenant DATA, so it follows core.process_manifest (V29), not the deployment-global
-- connector.entity_manifest: these rows describe resolved business identities within a tenant,
-- not a manifest declaration shared across tenants.

-- core.entity_identity — the crosswalk. Invariant 1 ("a source identity maps to at most one
-- active canonical entity") is not guarded by code: the PK makes it impossible to violate.
CREATE TABLE core.entity_identity (
    tenant_id    BIGINT      NOT NULL,
    entity_type  TEXT        NOT NULL,
    source       TEXT        NOT NULL,
    source_id    TEXT        NOT NULL,
    identity_ref UUID        NOT NULL DEFAULT gen_random_uuid(),
    entity_id    UUID,
    state        TEXT        NOT NULL,
    decided_by   TEXT,
    decided_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_entity_identity PRIMARY KEY (tenant_id, entity_type, source, source_id),
    -- identity_ref is the immutable surrogate an assertion addresses BEFORE a row resolves.
    CONSTRAINT uq_entity_identity_ref UNIQUE (identity_ref),
    -- exists solely so entity_assertion can carry a three-column FK (tenant/entity_type checked
    -- structurally, not merely by a guard).
    CONSTRAINT uq_entity_identity_ref_scoped UNIQUE (identity_ref, tenant_id, entity_type),
    CONSTRAINT ck_entity_identity_state
        CHECK (state IN ('resolved', 'ambiguous', 'unresolved_no_rule', 'unresolved_provider')),
    -- a non-resolved row claims no entity, and a resolved one always names one.
    CONSTRAINT ck_entity_identity_state_entity CHECK ((state = 'resolved') = (entity_id IS NOT NULL)),
    -- v1 cardinality: a canonical entity holds at most one identity per source. FULL index, not
    -- partial — NULLs do not collide in Postgres, so ambiguous rows (entity_id IS NULL) coexist
    -- without one, which is what keeps every ON CONFLICT inferring this index from breaking.
    CONSTRAINT uq_entity_identity_cardinality UNIQUE (tenant_id, entity_type, source, entity_id)
);
ALTER TABLE core.entity_identity ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_identity_tenant_isolation ON core.entity_identity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.entity_identity IS
    'Crosswalk: (entity type, source, source identifier) -> stable canonical entity id, with the '
    'resolution state that produced it.';

-- core.entity_identity_key — materialized, versioned fingerprints. One row per (identity, declared
-- rule): an upsert on rule_declared_id replaces the fingerprint in place rather than accumulating a
-- new row per rule version, which is why rule_version is a column and not part of the key.
CREATE TABLE core.entity_identity_key (
    tenant_id        BIGINT      NOT NULL,
    entity_type      TEXT        NOT NULL,
    source           TEXT        NOT NULL,
    source_id        TEXT        NOT NULL,
    rule_declared_id TEXT        NOT NULL,
    rule_version     TEXT        NOT NULL,
    key_fingerprint  BYTEA       NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_entity_identity_key PRIMARY KEY (tenant_id, entity_type, source, source_id, rule_declared_id),
    -- derived data with no meaning once its identity is gone — unlike an assertion, safe to cascade.
    CONSTRAINT fk_entity_identity_key_identity FOREIGN KEY (tenant_id, entity_type, source, source_id)
        REFERENCES core.entity_identity (tenant_id, entity_type, source, source_id) ON DELETE CASCADE
);
-- rule_version is part of the lookup key: a fingerprint computed under a stale version can never
-- satisfy a current-version lookup, so staleness fails closed rather than matching wrongly.
CREATE INDEX idx_entity_identity_key_lookup ON core.entity_identity_key
    (tenant_id, entity_type, rule_declared_id, rule_version, key_fingerprint);
ALTER TABLE core.entity_identity_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_identity_key_tenant_isolation ON core.entity_identity_key
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.entity_identity_key IS
    'One materialized, versioned HMAC fingerprint per (identity, declared cascade rule) — never '
    'the clear value.';

-- core.entity_assertion — human corrections. (A,B) and (B,A) collapse to one row by construction;
-- the three-column FK on each side makes a cross-tenant assertion structurally impossible, not
-- merely caught by a guard.
CREATE TABLE core.entity_assertion (
    id                 UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          BIGINT      NOT NULL,
    entity_type        TEXT        NOT NULL,
    left_identity_ref  UUID        NOT NULL,
    right_identity_ref UUID        NOT NULL,
    verdict            TEXT        NOT NULL,
    asserted_by        TEXT        NOT NULL,
    asserted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason             TEXT,
    CONSTRAINT pk_entity_assertion PRIMARY KEY (id),
    CONSTRAINT ck_entity_assertion_order CHECK (left_identity_ref < right_identity_ref),
    CONSTRAINT ck_entity_assertion_verdict CHECK (verdict IN ('same', 'distinct')),
    CONSTRAINT uq_entity_assertion_pair UNIQUE (tenant_id, entity_type, left_identity_ref, right_identity_ref),
    -- a human correction must never evaporate because a run cleaned up a source row.
    CONSTRAINT fk_entity_assertion_left FOREIGN KEY (left_identity_ref, tenant_id, entity_type)
        REFERENCES core.entity_identity (identity_ref, tenant_id, entity_type) ON DELETE RESTRICT,
    CONSTRAINT fk_entity_assertion_right FOREIGN KEY (right_identity_ref, tenant_id, entity_type)
        REFERENCES core.entity_identity (identity_ref, tenant_id, entity_type) ON DELETE RESTRICT
);
ALTER TABLE core.entity_assertion ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_assertion_tenant_isolation ON core.entity_assertion
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.entity_assertion IS
    'same/distinct human corrections addressing an immutable identity_ref, outranking every '
    'cascade rule on replay.';

-- core.entity_resolution_evidence — append-only ledger, same posture as
-- V47__audit_event_append_only.sql: no UPDATE/DELETE/TRUNCATE for lumnik_app; corrective
-- administration happens through a new event, never a rewrite.
--
-- identity_ref is a soft reference (ON DELETE SET NULL), deliberately NOT the hard, cascading FK
-- used by entity_identity_key: the ledger is audit history and must survive even if the crosswalk
-- row it once evidenced is later removed, which is exactly what an append-only table promises.
-- source/source_id are kept as plain denormalized columns (no FK) so a row stays queryable by its
-- own source context after the link is cleared.
--
-- tenant_id/entity_type DO join the FK (Copilot round 1 on PR #632): a single-column FK on
-- identity_ref alone only proves SOME identity somewhere carries that ref, not that it belongs to
-- the SAME tenant/entity_type this row itself declares — a row could otherwise misname its own
-- tenant relative to the identity it claims to evidence, and the FK would double as a cross-tenant
-- existence oracle. ON DELETE SET NULL names only identity_ref (PG15+ column-list syntax): this
-- row's own tenant_id/entity_type stay put so it remains queryable (and RLS-visible) once cleared.
CREATE TABLE core.entity_resolution_evidence (
    id               UUID             NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        BIGINT           NOT NULL,
    entity_type      TEXT             NOT NULL,
    source           TEXT             NOT NULL,
    source_id        TEXT             NOT NULL,
    identity_ref     UUID,
    rule_declared_id TEXT,
    rule_version     TEXT,
    provider         TEXT,
    score            DOUBLE PRECISION,
    candidates       JSONB            NOT NULL DEFAULT '[]'::jsonb,
    outcome          TEXT             NOT NULL,
    decided_by       TEXT             NOT NULL,
    run_id           UUID,
    occurred_at      TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_entity_resolution_evidence PRIMARY KEY (id),
    CONSTRAINT ck_entity_resolution_evidence_outcome
        CHECK (outcome IN ('resolved', 'ambiguous', 'unresolved_no_rule', 'unresolved_provider')),
    CONSTRAINT ck_entity_resolution_evidence_decided_by CHECK (decided_by IN ('automatic', 'asserted')),
    CONSTRAINT fk_entity_resolution_evidence_identity FOREIGN KEY (identity_ref, tenant_id, entity_type)
        REFERENCES core.entity_identity (identity_ref, tenant_id, entity_type)
        ON DELETE SET NULL (identity_ref)
);
CREATE INDEX idx_entity_resolution_evidence_identity ON core.entity_resolution_evidence
    (tenant_id, entity_type, source, source_id, occurred_at);
ALTER TABLE core.entity_resolution_evidence ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_resolution_evidence_tenant_isolation ON core.entity_resolution_evidence
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
COMMENT ON TABLE core.entity_resolution_evidence IS
    'Append-only ledger of resolution decisions: candidates considered, rule/version, provider and '
    'score where relevant, and whether the decision was automatic or asserted.';

-- Guarded on the role's existence, as V15/V47 do: the shared DevServices container has no such role.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_app') THEN
    REVOKE UPDATE, DELETE, TRUNCATE ON core.entity_resolution_evidence FROM lumnik_app;
  END IF;
END $$;
