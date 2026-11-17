-- V98 — The hybrid tables the hub made (#2010).
-- HybridJsonbWriter writes into a table a target names: ext.<name> or connector.<name>. It used to judge an existing
-- one by its shape (the writer's columns and its UNIQUE (tenant_id, external_id) constraint), which any DDL can copy.
-- From now on the writer records each table in the same transaction that creates it, and writes, repairs, purges by
-- endpoint and drops on connector delete only a table recorded here. No tenant column: a table name is deployment-wide.
-- The rows before this version are adopted by their shape, as the writer judged them until now: every table of that
-- shape under ext (only the writer creates ext), and under connector those a hybrid registration names.
-- The ext schema is created here, with its USAGE, instead of at each table's creation: no migration granted it, and
-- without it lumnik_app and ask-to-SQL reach nothing inside, whatever a table's own grants say. Idempotent.

CREATE SCHEMA IF NOT EXISTS ext;
GRANT USAGE ON SCHEMA ext TO lumnik_app;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    GRANT USAGE ON SCHEMA ext TO lumnik_readonly;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS connector.hybrid_table (
    table_schema TEXT NOT NULL,
    table_name   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_schema, table_name)
);

-- The endpoint purge door reads it as lumnik_app, which writes nothing in it: a row it could add would hand the admin
-- writer any table under connector to alter, grant and drop. The default privileges give it DML on a new table.
GRANT SELECT ON connector.hybrid_table TO lumnik_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON connector.hybrid_table FROM lumnik_app;

INSERT INTO connector.hybrid_table (table_schema, table_name)
SELECT n.nspname, c.relname
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND (n.nspname = 'ext'
        OR (n.nspname = 'connector' AND EXISTS (
              -- A registration keeps the target's whole name, Postgres its first 63 characters.
              SELECT 1 FROM connector.dynamic_table d
               WHERE d.table_schema = n.nspname AND left(d.table_name, 63) = c.relname
                 AND d.mapping_id = 'hybrid:' || d.table_schema || '.' || d.table_name)))
   -- The columns the writer writes, with their types (row_hash aside: the writer adds it to an old table), and its id,
   -- whose sequence it grants.
   AND (SELECT count(*) FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
           AND (a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod)) IN (
                 ('id', 'bigint'), ('tenant_id', 'bigint'), ('external_id', 'text'), ('data', 'jsonb'),
                 ('connector_id', 'uuid'), ('endpoint_id', 'uuid'))) = 6
   -- Its key: the table constraint, on exactly those two columns, which the upsert's ON CONFLICT needs.
   AND EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k
                WHERE k.conrelid = c.oid AND k.contype = 'u' AND NOT k.condeferrable
                  AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_catalog.pg_attribute a
                        WHERE a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)) = ARRAY['external_id', 'tenant_id'])
ON CONFLICT DO NOTHING;
