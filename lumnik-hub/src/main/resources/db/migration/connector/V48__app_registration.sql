-- V48 — runtime-declared métier apps (kind:App): the PWA's own identity, moved out of the
-- compiled METIERS map in src/config.mjs and onto the app ladder. Deployment-global like
-- saved_view: the manifest is shared, the DATA it points at stays tenant-scoped by RLS, and
-- access control lives in the resources (ScopeGrants), not in the row.
CREATE TABLE connector.app_registration (
    name             TEXT        PRIMARY KEY,
    scope            TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    icon             TEXT        NOT NULL,
    accent_color     TEXT        NOT NULL,
    background_color TEXT        NOT NULL,
    is_default       BOOLEAN     NOT NULL DEFAULT false,
    yaml_text        TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_registration_scope ON connector.app_registration (scope);

-- AT MOST one default, enforced here. AT LEAST one is left to the apply door instead of a
-- deferred CONSTRAINT TRIGGER counting is_default at commit — not because Postgres can't express
-- it, but because this schema has zero triggers across every migration, and the two invariants'
-- failure modes are not symmetric. "At most one" is broken only by a rare concurrent race
-- (AppRegistrationStore.upsert's javadoc already documents and accepts the resulting 500). "At
-- least one" is broken by the most ordinary first-day mistake — re-applying the current default
-- with default: false, or omitting default: on a re-apply, since it is just another field. That
-- is not a race, it is the expected shape of a wrong first attempt, and it deserves the door's
-- clean 400 "cannot remove the last default app — …" rather than a constraint violation
-- surfacing as an opaque IllegalStateException.
CREATE UNIQUE INDEX app_registration_one_default
    ON connector.app_registration (is_default) WHERE is_default;

-- The registry REPLACES the compiled METIERS map, so it cannot start empty: after this migration
-- an app that is not a row here does not exist at all — Clients and Stock included. These two
-- seeds are today's compiled values verbatim (same titles, same icons) with the shared
-- --accent/--bg from styles.css, so a deployment that has run V48 and applied no kind:App
-- manifest yet boots exactly as it did before. Seeding anything prettier would be a silent,
-- unrequested visual change on deploy, now that the theme fields are actually applied.
INSERT INTO connector.app_registration
    (name, scope, title, icon, accent_color, background_color, is_default, yaml_text) VALUES
    ('clients', 'clients', 'Clients', 'clients', '#8b7ff0', '#14121f', true,
     '# seeded at V48 — a shipped built-in, no integrator-authored manifest'),
    ('stock',   'stock',   'Stock',   'stock',   '#8b7ff0', '#14121f', false,
     '# seeded at V48 — a shipped built-in, no integrator-authored manifest');
