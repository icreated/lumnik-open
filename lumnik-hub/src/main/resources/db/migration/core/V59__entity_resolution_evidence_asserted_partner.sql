-- icreated/lumnik#737: which specific `same` assertion partner produced a resolved-via-assertion
-- decision, so a later retraction of THAT SPECIFIC pair — not merely a partner's live resolution
-- state changing for some unrelated reason — can be told apart from an ordinary "never silently
-- recycle" refusal (CascadeResolver.resolveWithin).
--
-- Soft reference (ON DELETE SET NULL), same posture as this table's own identity_ref column
-- (V49): the ledger is audit history and must survive even if the crosswalk row it names is later
-- removed. tenant_id/entity_type join the FK for the same reason identity_ref's own FK does — a
-- single-column FK alone would only prove SOME identity somewhere carries that ref, not that it
-- belongs to the SAME tenant/entity_type this row itself declares.
ALTER TABLE core.entity_resolution_evidence ADD COLUMN asserted_partner_ref UUID;
ALTER TABLE core.entity_resolution_evidence
    ADD CONSTRAINT fk_entity_resolution_evidence_asserted_partner
    FOREIGN KEY (asserted_partner_ref, tenant_id, entity_type)
    REFERENCES core.entity_identity (identity_ref, tenant_id, entity_type)
    ON DELETE SET NULL (asserted_partner_ref);

COMMENT ON COLUMN core.entity_resolution_evidence.asserted_partner_ref IS
    'The single same-assertion partner identity_ref responsible for a sameAssertionResolved '
    'decision, when unambiguous — null for every other event. Lets a later retraction of THAT '
    'specific pair be told apart from an unrelated change in the partner''s own live resolution '
    'state (CascadeResolver.resolveWithin''s explicitlyRetracted exemption).';
