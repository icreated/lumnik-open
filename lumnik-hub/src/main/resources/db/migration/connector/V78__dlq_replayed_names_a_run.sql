-- V78 — A dead letter closed REPLAYED names the run that carried the row (#1377).
-- The hub never re-reads a dead letter: a REPLAYED with no run was a discard under another word.
-- Entries closed that way before this version are rewritten as what they were, DISCARDED, then
-- the table refuses the shape for good, whoever writes it.

UPDATE connector.connector_dead_letter
   SET resolution = 'DISCARDED', updated_at = now()
 WHERE resolution = 'REPLAYED' AND resolved_run_id IS NULL;

ALTER TABLE connector.connector_dead_letter
  ADD CONSTRAINT connector_dead_letter_replayed_names_a_run
  CHECK (resolution IS DISTINCT FROM 'REPLAYED' OR resolved_run_id IS NOT NULL);
