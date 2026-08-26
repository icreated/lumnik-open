-- V39: entity_id BIGINT -> entity_key TEXT, on both audit_outbox and audit_event.
--
-- core.audit_event is empty in every shipped deployment (verified during the docs audit that
-- produced this arc, #256 lot 4) — no @Auditable entity has ever been wired, so there is no row
-- to preserve and no rewrite to perform. This shape will never be this cheap to change again.
--
-- Why entity_id BIGINT was wrong: Secret's id is a UUID (the listener's Long cast on it fails
-- and the fail-soft catch silently swallows the exception — "audited" while recording nothing),
-- and an entity READ identifies its subject by name, not by a numeric id at all. A Long, a UUID
-- and an entity name all fit a text key; nothing fits a BIGINT.
--
-- audit_event is PARTITION BY RANGE (occurred_at). Verified live (docker pgvector/pgvector:pg16,
-- matching devservices) that ALTER TABLE ... RENAME COLUMN and ALTER COLUMN ... TYPE, run against
-- the partitioned PARENT (not ONLY), cascade to every partition without a separate statement per
-- partition — confirmed via \d on both the parent and core.audit_event_default afterward, both
-- showing entity_key TEXT. The index is dropped and recreated rather than left renamed-in-place,
-- so its (auto-generated) name reflects the new column instead of a stale "entity_id".

ALTER TABLE core.audit_outbox RENAME COLUMN entity_id TO entity_key;
ALTER TABLE core.audit_outbox ALTER COLUMN entity_key TYPE TEXT USING entity_key::text;

ALTER TABLE core.audit_event RENAME COLUMN entity_id TO entity_key;
ALTER TABLE core.audit_event ALTER COLUMN entity_key TYPE TEXT USING entity_key::text;

DROP INDEX core.audit_event_tenant_id_entity_type_entity_id_occurred_at_idx;
CREATE INDEX ON core.audit_event (tenant_id, entity_type, entity_key, occurred_at DESC);
