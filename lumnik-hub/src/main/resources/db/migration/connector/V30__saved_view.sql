-- V30 — saved kind:View manifests: the server-side home renderers without a
-- filesystem (the PWA) discover views from. Deployment-global like
-- entity_manifest: manifests are shared; the DATA stays tenant-scoped by RLS.
CREATE TABLE connector.saved_view (
    name        TEXT        PRIMARY KEY,
    scope       TEXT        NOT NULL,
    title       TEXT,
    yaml_text   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_view_scope ON connector.saved_view (scope);
