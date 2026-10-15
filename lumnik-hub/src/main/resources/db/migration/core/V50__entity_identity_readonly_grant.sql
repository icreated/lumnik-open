-- #618: EntityViewCompiler's resolution: projection LEFT JOINs core.entity_identity from inside a
-- connector.v_<name> view. The view stays security_invoker = true, so a reader querying it needs
-- SELECT on core.entity_identity itself, not merely on the view.
--
-- lumnik_app already has it for free: 01-roles.sh's ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin
-- covers every table lumnik_admin (the Flyway migration user) creates, in any schema. lumnik_readonly
-- has no such blanket grant — V15__schema_card_and_readonly.sql scoped its
-- ALTER DEFAULT PRIVILEGES to schema connector only, because at the time nothing outside connector
-- needed to be askable. core.entity_identity is the first core table a v1 view can join, so without
-- this grant ask-to-SQL (which executes as lumnik_readonly, SqlGuard layer 1) hits "permission
-- denied for table entity_identity" the moment a resolved entity is queried.
--
-- Guarded on the role's existence, same posture as V15/V47: the shared DevServices container has
-- no such role.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    GRANT SELECT ON core.entity_identity TO lumnik_readonly;
  END IF;
END $$;
