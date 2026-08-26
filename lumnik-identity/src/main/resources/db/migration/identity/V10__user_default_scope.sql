-- V10__user_default_scope.sql — per-user default RAG scope (single tag, k9s-style)
ALTER TABLE identity.app_user
    ADD COLUMN default_scope TEXT NULL;
