-- V46 — A deleted integration stops holding its name. Same defect as V44 and V45, third table.
--
-- IntegrationResource.delete soft-deletes, and every reader is live-only (listLiveForTenant,
-- findLiveForTenant, findLiveById, findEnabledForEvent). uq_webhook_endpoint_name counted the
-- tombstone, so re-creating an integration under a name that had been removed died at commit with
-- 23505 and answered an anonymous 500 — the name was absent from the list and unusable at once.
--
-- The predicate is deleted_at alone, because deleted_at alone is what the delete door stamps and
-- what every reader filters — and IntegrationResource's new name check reads exactly this, so the
-- door and the index refuse the same set. It must not GROW to include archived_at if archiving is
-- ever wired to this table (nothing in src/main calls archive() on a WebhookEndpoint today):
-- archiving is reversible, so an archived row has to keep holding its name or unarchiving it would
-- collide with whatever took the name meanwhile.

ALTER TABLE core.webhook_endpoint DROP CONSTRAINT uq_webhook_endpoint_name;
CREATE UNIQUE INDEX uq_webhook_endpoint_name ON core.webhook_endpoint (tenant_id, name)
    WHERE deleted_at IS NULL;
