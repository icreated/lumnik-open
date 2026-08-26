CREATE TABLE connector.entity_manifest (
    name        TEXT        PRIMARY KEY,
    yaml_text   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
