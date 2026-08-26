-- External-IdP JIT provisioning: stable (issuer, subject) identity on app_user.
-- Nullable so local/password users (NULL idp) are untouched; PG NULLS DISTINCT means
-- many NULL rows coexist under the unique constraint.
ALTER TABLE identity.app_user
    ADD COLUMN idp_issuer  varchar(255),
    ADD COLUMN idp_subject varchar(255);

ALTER TABLE identity.app_user
    ADD CONSTRAINT uq_app_user_idp UNIQUE (tenant_id, idp_issuer, idp_subject);

-- External identity is keyed on the PAIR: both columns are set (external user) or both NULL
-- (local user). NULLS-DISTINCT in the unique constraint would otherwise allow a half-populated
-- row that breaks (issuer, subject) lookups and uniqueness.
ALTER TABLE identity.app_user
    ADD CONSTRAINT ck_app_user_idp_pair CHECK ((idp_issuer IS NULL) = (idp_subject IS NULL));
