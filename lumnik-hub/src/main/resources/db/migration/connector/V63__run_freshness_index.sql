-- V63__run_freshness_index.sql — the freshness dial reads one run per connector (#907).
--
-- ScopeFreshness dates a scope's data ("data as of …") on every view render and every ask that
-- reaches rows: the newest ended_at among the Completed or Partial runs of the scope's connectors.
-- Written as a MAX over a join it read every such run — PostgreSQL's MIN/MAX-through-an-index
-- rewrite applies to a single table only — and connector_run is never purged (#892).
--
-- The query now asks each connector for its newest landed run (LATERAL … ORDER BY ended_at DESC
-- LIMIT 1) and takes the MAX of those. This index is that walk: one connector's landed runs, newest
-- first, so each connector costs one probe whatever its history. The predicate is the query's status
-- filter verbatim, which lets the planner prove the query implies it.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run in one (V36).

CREATE INDEX idx_run_connector_ended ON connector.connector_run (connector_id, ended_at DESC)
    WHERE status IN ('Completed', 'Partial');
