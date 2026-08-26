-- V37: a claim that outlives its transaction, so the retry sweep stops holding one across HTTP.
--
-- The retry sweep POSTs to a CUSTOMER's endpoint. Until now the whole batch — up to 100
-- deliveries at 5s connect + 10s request each — ran inside ONE transaction, because its
-- cross-instance protection was findRetryable's FOR UPDATE SKIP LOCKED and a row lock dies
-- with its transaction. @Scheduled(SKIP) is per-JVM only, so on a multi-replica deployment
-- that lock was the only thing stopping a customer receiving the same POST twice; removing
-- the transaction to free the pooled connection would have removed the protection with it.
--
-- A claim COLUMN survives its transaction. The sweep now claims one row in a short
-- transaction, releases the connection, delivers with nothing held, and writes the outcome
-- in a second short transaction. Correctness and the pool both keep what they had.
--
-- NULL = unclaimed. A value in the future = claimed by a sweep, invisible to every other
-- sweep. A value in the past = expired and reclaimable — without that, a process that dies
-- mid-delivery would strand its row forever.

ALTER TABLE core.webhook_delivery ADD COLUMN claimed_until TIMESTAMPTZ;

COMMENT ON COLUMN core.webhook_delivery.claimed_until IS
    'Retry lease: a sweep owns this delivery until this instant. NULL = unclaimed, past = expired and reclaimable.';

-- No new index. V24's partial idx_webhook_delivery_retry (id) WHERE succeeded = FALSE AND
-- attempts < 3 still serves the claim query: its predicate is implied by the claim predicate
-- and its key matches the ORDER BY id. claimed_until is a residual filter over an index that
-- only ever holds unresolved deliveries (rows leave it once they succeed or cap), and at most
-- one row per instance is claimed at a time — indexing it would widen every insert for nothing.
