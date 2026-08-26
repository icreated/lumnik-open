-- V8: per-tenant script content override
ALTER TABLE core.hook_tenant_override
    ADD COLUMN script_content TEXT;
