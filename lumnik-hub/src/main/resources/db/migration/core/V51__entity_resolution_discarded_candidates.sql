-- #613/#621: a CandidateProvider may propose an id absent from the crosswalk (a stale search
-- index) — that candidate is discarded and must never count toward the decision, but the
-- discrepancy still needs a durable, auditable record.
--
-- A separate column, not a richer `candidates` shape: EntityIdentityRepository.lastResolvedEntityId
-- reads `candidates ->> 0` as a bare UUID string for every `outcome = 'resolved'` row, exact or
-- propose alike (the "never silently recycle a resolved link" guard's data source) — mixing object
-- shapes into that array would either break that parse or leave an inconsistent shape depending on
-- outcome. `candidates` keeps its existing flat-UUID-array format, untouched, for every rule type.
ALTER TABLE core.entity_resolution_evidence
    ADD COLUMN discarded_candidates JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN core.entity_resolution_evidence.discarded_candidates IS
    'Candidates a provider proposed but the policy discarded (id absent from the crosswalk), each '
    'as {"entityId":..,"score":..} — never counted toward the decision, kept for audit.';
