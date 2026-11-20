-- V100 — A unique index a connector declares binds its own tenant only (#2088).
-- V96 led each declared index by tenant_id, but a unique one stayed one object for the whole t_* table:
-- a tenant that declared nothing could not hold two rows with the same value. createIndexes now makes it
-- partial, WHERE tenant_id = <tenant>, under <table>_uidx_<columns>_<tenant> (cut at 63 bytes as Java cuts it).
-- Each shared unique declared index (name <t_*>_idx_..., btree, led by tenant_id, no predicate) is rebuilt
-- here (found as V96 finds them) once per tenant the registry (connector.dynamic_table) holds on its
-- table, then dropped. Which tenant declared it is not recorded in the database, so each registered
-- tenant keeps the rule it already had: no existing row breaks it. A tenant that never declared it may drop its own copy.
-- One a constraint holds, the replica identity uses, or with no tenant registered on its table, is left
-- and named in a WARNING. Idempotent: a rebuilt index has a predicate.

DO $$
DECLARE
  i record;
  t bigint;
  base text;
  suffix text;
  idx_name text;
  built boolean;
BEGIN
  FOR i IN
    SELECT ix.indexrelid::regclass AS idx, ix.indrelid::regclass AS rel, tc.relname AS tbl,
           ix.indisreplident OR EXISTS (SELECT 1 FROM pg_catalog.pg_constraint con
                                         WHERE con.conindid = ix.indexrelid) AS pinned,
           (SELECT array_agg(a.attname ORDER BY k.ord)
              FROM unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
              JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
             WHERE a.attname <> 'tenant_id') AS cols
      FROM pg_catalog.pg_index ix
      JOIN pg_catalog.pg_class ic ON ic.oid = ix.indexrelid
      JOIN pg_catalog.pg_class tc ON tc.oid = ix.indrelid
      JOIN pg_catalog.pg_namespace ns ON ns.oid = tc.relnamespace
     WHERE ns.nspname = 'connector'
       AND left(tc.relname, 2) = 't_'
       AND left(ic.relname, least(length(tc.relname) + 5, 63)) = left(tc.relname || '_idx_', 63)
       AND ix.indisunique
       AND ix.indpred IS NULL
       AND ix.indexprs IS NULL
       AND ix.indnatts > 1
       AND ic.relam = (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'btree')
       AND (SELECT a.attname FROM pg_catalog.pg_attribute a
             WHERE a.attrelid = ix.indrelid AND a.attnum = ix.indkey[0]) = 'tenant_id'
  LOOP
    IF i.pinned THEN
      RAISE WARNING 'V100 (#2088): % is held by a constraint or the replica identity, so it was not rebuilt per '
                    'tenant; rebuild it by hand, WHERE tenant_id = <tenant>', i.idx;
      CONTINUE;
    END IF;
    base := i.tbl || '_uidx_' || array_to_string(i.cols, '_');
    built := false;
    FOR t IN SELECT DISTINCT d.tenant_id FROM connector.dynamic_table d
              WHERE d.table_schema = 'connector' AND d.table_name = i.tbl LOOP
      suffix := '_' || t;
      idx_name := CASE WHEN length(base || suffix) <= 63 THEN base || suffix
                       ELSE left(base, 63 - length(suffix) - 13) || '_'
                            || left(encode(sha256(convert_to(base, 'UTF8')), 'hex'), 12) || suffix END;
      EXECUTE format('CREATE UNIQUE INDEX IF NOT EXISTS %I ON %s (%s) WHERE tenant_id = %s', idx_name, i.rel,
                     (SELECT string_agg(quote_ident(c), ', ') FROM unnest(i.cols) AS c), t);
      built := true;
    END LOOP;
    IF NOT built THEN
      RAISE WARNING 'V100 (#2088): no tenant is registered on the table of %, so it was not rebuilt per tenant; '
                    'rebuild it by hand, WHERE tenant_id = <tenant>', i.idx;
      CONTINUE;
    END IF;
    EXECUTE format('DROP INDEX %s', i.idx);
  END LOOP;
END $$;
