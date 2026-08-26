-- V15__drop_session_table.sql — the Session aggregate is gone.
-- Sessions live in Keycloak since authentication became OIDC-only; the entity was removed and
-- nothing has read or written this table since. Its RLS policy, indexes and foreign keys drop
-- with it (V2 created it, V4/V5/V7 patched it, V6 enabled RLS on it).
DROP TABLE IF EXISTS identity.session;
