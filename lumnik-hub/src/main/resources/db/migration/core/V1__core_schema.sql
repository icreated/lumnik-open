CREATE SCHEMA IF NOT EXISTS core;

-- Module activation per tenant
CREATE TABLE core.installed_module (
    tenant_id   BIGINT      NOT NULL,
    module_id   VARCHAR(50) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, module_id)
);

-- Per-tenant key/value settings
CREATE TABLE core.tenant_setting (
    tenant_id   BIGINT       NOT NULL,
    key         VARCHAR(120) NOT NULL,
    value       TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, key)
);

-- JSON patches over static screen metadata
CREATE TABLE core.tenant_customization (
    tenant_id   BIGINT       NOT NULL,
    module_id   VARCHAR(50)  NOT NULL,
    screen_id   VARCHAR(100) NOT NULL,
    patch       JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, module_id, screen_id)
);

-- Transactional audit outbox (written in same tx as entity change)
CREATE TABLE core.audit_outbox (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL,
    actor_id    BIGINT,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   BIGINT       NOT NULL,
    operation   VARCHAR(20)  NOT NULL,  -- CREATE / UPDATE / DELETE / ARCHIVE
    changes     JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    dispatched  BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ON core.audit_outbox (dispatched, created_at) WHERE dispatched = FALSE;

-- Append-only audit log (partitioned by month, populated by dispatcher)
CREATE TABLE core.audit_event (
    id          BIGSERIAL,
    tenant_id   BIGINT       NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    actor_id    BIGINT,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   BIGINT       NOT NULL,
    operation   VARCHAR(20)  NOT NULL,
    changes     JSONB        NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Bootstrap first partition (current month)
CREATE TABLE core.audit_event_default PARTITION OF core.audit_event DEFAULT;

CREATE INDEX ON core.audit_event (tenant_id, entity_type, entity_id, occurred_at DESC);

-- Transactional domain-event outbox (for broker dispatch)
CREATE TABLE core.event_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    event_type      VARCHAR(120) NOT NULL,
    payload         JSONB        NOT NULL,
    idempotency_key VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    UNIQUE (idempotency_key)
);
CREATE INDEX ON core.event_outbox (published_at, created_at) WHERE published_at IS NULL;
