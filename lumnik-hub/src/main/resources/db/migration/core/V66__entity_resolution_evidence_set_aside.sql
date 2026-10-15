-- #920: an entity that already holds another row of the row's source can never take the row (one
-- identity per source, uq_entity_identity_cardinality — #889), so the resolver sets it aside before
-- the decision: from an exact rule's candidates, from a provider's proposals, and from the partners
-- of a `same` assertion. The decision was right, but the ledger said nothing of it — a duplicated
-- ERP row that mints its own entity read as a plain fresh mint.
--
-- A column of its own, neither `candidates` nor `discarded_candidates`: `candidates ->> 0` is the
-- entity a resolved event resolved to (EntityIdentityRepository.lastResolvedEvidence), and
-- `discarded_candidates` is the provider SPI's stale-index discard (V51), a different thing.
ALTER TABLE core.entity_resolution_evidence
    ADD COLUMN set_aside JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN core.entity_resolution_evidence.set_aside IS
    'Entities the resolver set aside because they already hold another row of this source, each as '
    '{"entityId":..,"reason":"same-source","rule":..} — rule is the cascade rule that found it, null '
    'for a partner of a same assertion. Never counted toward the decision, kept for audit.';
