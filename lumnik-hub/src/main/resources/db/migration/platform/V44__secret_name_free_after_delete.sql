-- V44 — A deleted secret stops holding its name.
--
-- `lm secret rm` is a SOFT delete: SecretResource.delete stamps deleted_at and the row stays.
-- Everything that READS platform.secret filters that row out — SecretRepository.findByName and
-- listByTenant, PgcryptoSecretResolver's decrypt, and both indexes V14 declared. UNIQUE
-- (tenant_id, name) was the one rule that did not: it still counted the tombstone, so the create
-- door's INSERT raised 23505 and the caller got an anonymous 500 for a name `list` no longer
-- showed and `rm` answered 404 for. The name was unusable and nothing could free it.
--
-- The rule now says what the readers say. This is the policy V32 states for core.workflow_manifest,
-- read in the other direction: there, a plain UNIQUE agrees with the pre-check because the app
-- cannot reach the filtered state. Here it reaches it on every delete.
--
-- idx_secret_name goes because the new index IS it, plus uniqueness: same columns, same predicate.
-- The rebuild also keeps what the constraint was doing besides burning names — under READ
-- COMMITTED it is what turns two concurrent creates of one name into a 23505 instead of two
-- coexisting live rows, and a partial unique index enforces that just as well.

ALTER TABLE platform.secret DROP CONSTRAINT secret_tenant_id_name_key;
DROP INDEX platform.idx_secret_name;
CREATE UNIQUE INDEX idx_secret_name ON platform.secret (tenant_id, name) WHERE deleted_at IS NULL;
