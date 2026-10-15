-- V61__search_index_one_per_entity.sql — one kind:SearchIndex per Entity (#832).
--
-- Two declarations over one entity both reach the provider, and a hit comes back once per index
-- (PgSearchProvider ranks over every eligible generation, OpenSearch queries every alias at once):
-- the same document twice, with two scores that are not comparable. The rule belongs in the table,
-- not in a check the apply door could be walked around: a second index is expressed by mixing
-- `match` modes field by field inside the first.
--
-- An install that already holds duplicates must still boot. Per (tenant, entity) the earliest VALID
-- declaration is kept (the earliest, when none is valid), so one that no longer works never outlives one
-- that does. The others are deleted the way the delete door deletes them — one purge obligation each, in
-- the same statement, so documents already projected are purged too. The `search_index_purge` rows name
-- what was dropped.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY tenant_id, lower(entity)
            ORDER BY CASE status WHEN 'valid' THEN 0 ELSE 1 END, created_at, id) AS rn
    FROM core.search_index
), gone AS (
    DELETE FROM core.search_index s
    USING ranked r
    WHERE s.id = r.id AND r.rn > 1
    RETURNING s.tenant_id, s.name, s.entity
)
INSERT INTO core.search_index_purge (tenant_id, name, entity)
SELECT tenant_id, name, entity FROM gone;

CREATE UNIQUE INDEX search_index_one_per_entity ON core.search_index (tenant_id, lower(entity));
