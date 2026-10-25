-- icreated/lumnik#1520: who asserted an asserted decision and why, copied onto the ledger row when the
-- decision is recorded. core.entity_assertion is one row per pair, upserted by every later correction:
-- read through a join, an earlier history line took the pair's CURRENT asserted_by/reason, so a later
-- assert-distinct rewrote the audit trail. Both columns are null on an automatic decision.
ALTER TABLE core.entity_resolution_evidence ADD COLUMN asserted_by TEXT;
ALTER TABLE core.entity_resolution_evidence ADD COLUMN assertion_reason TEXT;

COMMENT ON COLUMN core.entity_resolution_evidence.asserted_by IS
    'The user id of the assertion this asserted decision acted on, as it stood when the row was written.';
COMMENT ON COLUMN core.entity_resolution_evidence.assertion_reason IS
    'The reason of that assertion, as it stood when the row was written.';

-- Rows written before this column existed: filled only where the pair's assertion provably has not
-- changed since the event (asserted_at is reset by every upsert). Any other old row stays empty rather
-- than borrow a correction made after it.
UPDATE core.entity_resolution_evidence e
   SET asserted_by = a.asserted_by, assertion_reason = a.reason
  FROM core.entity_assertion a
 WHERE a.tenant_id = e.tenant_id
   AND a.entity_type = e.entity_type
   AND e.decided_by = 'asserted'
   AND e.identity_ref IS NOT NULL
   AND e.asserted_partner_ref IS NOT NULL
   AND a.left_identity_ref = LEAST(e.identity_ref, e.asserted_partner_ref)
   AND a.right_identity_ref = GREATEST(e.identity_ref, e.asserted_partner_ref)
   AND a.asserted_at <= e.occurred_at;
