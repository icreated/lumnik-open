-- V80 — A run records the versions of the Kotlin scripts its rows went through (#1430).
-- The operator replaces a hooks: script and nothing said which version produced which row: the run's
-- definition_sha256 covers its manifest, which names the script but not its text. [{name, sha256}], null when
-- no script ran. Nullable, no default: no rewrite.

ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS scripts JSONB;
