-- V96 — An index a connector declares leads with tenant_id (#1990).
-- A t_* table holds every tenant's rows, but a declared index covered the declared columns alone:
-- a unique one let one tenant's key refuse another tenant's row. Each such index is rebuilt under
-- its own name as (tenant_id, <its columns>), unique when it was, with its collations, storage
-- options, tablespace, CLUSTER mark and comment. Found by name (<t_*>_idx_..., as createIndexes gives it, cut at 63
-- bytes like any identifier) and by the shape createIndexes makes: a btree over plain columns,
-- default operator classes and order, no INCLUDE, no predicate, NULLS DISTINCT, on a table with a
-- tenant_id column, not led by it. Anything else is left as it is.
-- Of those, one a constraint holds or a foreign key references cannot be dropped; dropping the
-- replica identity's would change what logical replication sends; one in a tablespace the
-- migration's role may not create in cannot be rebuilt there. Each is left, and named in a
-- WARNING, to rebuild by hand.
-- Every new index is built first, under a temporary name, so the tables can still be read; the
-- old ones are then dropped and the new ones renamed. Writes to these tables wait for the whole
-- migration, reads for the swap at its end; until it commits, both copies of each index exist.
-- A per-tenant key is weaker than the old one: no existing row breaks it. Idempotent: a rebuilt
-- index is led by tenant_id.

DO $$
DECLARE
  i record;
  cols text;
  old_idx regclass[] := '{}';
  tmp_name text[] := '{}';
  final_name text[] := '{}';
  on_rel regclass[] := '{}';
  clustered boolean[] := '{}';
  note text[] := '{}';
  k int;
BEGIN
  FOR i IN
    SELECT ix.indexrelid::regclass AS idx, ix.indrelid::regclass AS rel, ix.indisunique AS uniq,
           ic.relname AS idxname, ix.indkey::int2[] AS keys, ix.indcollation::oid[] AS colls,
           'v96_' || ix.indexrelid AS tmp,
           CASE WHEN ic.reloptions IS NULL THEN ''
                ELSE ' WITH (' || array_to_string(ic.reloptions, ', ') || ')' END AS storage,
           ' TABLESPACE ' || quote_ident(ts.spcname) AS tablespace,
           ix.indisclustered AS clustered, obj_description(ix.indexrelid, 'pg_class') AS note,
           ix.indisreplident
             OR EXISTS (SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = ix.indexrelid)
             OR (ic.reltablespace <> 0 AND NOT has_tablespace_privilege(ic.reltablespace, 'CREATE')) AS pinned
      FROM pg_catalog.pg_index ix
      JOIN pg_catalog.pg_class ic ON ic.oid = ix.indexrelid
      JOIN pg_catalog.pg_class tc ON tc.oid = ix.indrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = tc.relnamespace
      JOIN pg_catalog.pg_tablespace ts ON ts.oid = COALESCE(NULLIF(ic.reltablespace, 0),
           (SELECT d.dattablespace FROM pg_catalog.pg_database d WHERE d.datname = current_database()))
     WHERE n.nspname = 'connector'
       AND left(tc.relname, 2) = 't_'
       AND left(ic.relname, least(length(tc.relname) + 5, 63)) = left(tc.relname || '_idx_', 63)
       AND ix.indpred IS NULL
       AND ix.indexprs IS NULL
       AND ic.relam = (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'btree')
       AND ix.indnkeyatts = ix.indnatts
       AND NOT ix.indnullsnotdistinct
       AND NOT EXISTS (SELECT 1 FROM unnest(ix.indoption::int2[]) o WHERE o <> 0)
       AND NOT EXISTS (SELECT 1 FROM unnest(ix.indclass::oid[]) c
                         JOIN pg_catalog.pg_opclass oc ON oc.oid = c WHERE NOT oc.opcdefault)
       AND EXISTS (SELECT 1 FROM pg_catalog.pg_attribute ta
                    WHERE ta.attrelid = ix.indrelid AND ta.attname = 'tenant_id')
       AND (SELECT a.attname FROM pg_catalog.pg_attribute a
             WHERE a.attrelid = ix.indrelid AND a.attnum = ix.indkey[0]) <> 'tenant_id'
  LOOP
    IF i.pinned THEN
      RAISE WARNING 'V96 (#1990): % is held by a constraint, a foreign key, the replica identity or its '
                    'tablespace, so it was not rebuilt per tenant; rebuild it by hand with tenant_id first', i.idx;
      CONTINUE;
    END IF;
    SELECT string_agg(quote_ident(a.attname)
                      || CASE WHEN k.coll <> 0 AND k.coll <> a.attcollation
                              THEN ' COLLATE ' || k.coll::regcollation::text ELSE '' END,
                      ', ' ORDER BY k.ord) INTO cols
      FROM unnest(i.keys, i.colls) WITH ORDINALITY AS k(attnum, coll, ord)
      JOIN pg_catalog.pg_attribute a ON a.attrelid = i.rel AND a.attnum = k.attnum
     WHERE a.attname <> 'tenant_id';
    EXECUTE format('CREATE %sINDEX %I ON %s (tenant_id, %s)%s%s',
                   CASE WHEN i.uniq THEN 'UNIQUE ' ELSE '' END, i.tmp, i.rel, cols, i.storage, i.tablespace);
    old_idx := old_idx || i.idx;
    tmp_name := tmp_name || i.tmp;
    final_name := final_name || i.idxname;
    on_rel := on_rel || i.rel;
    clustered := clustered || i.clustered;
    note := note || i.note;
  END LOOP;
  FOR k IN 1 .. cardinality(old_idx) LOOP
    EXECUTE format('DROP INDEX %s', old_idx[k]);
    EXECUTE format('ALTER INDEX connector.%I RENAME TO %I', tmp_name[k], final_name[k]);
    IF clustered[k] THEN
      EXECUTE format('ALTER TABLE %s CLUSTER ON %I', on_rel[k], final_name[k]);
    END IF;
    IF note[k] IS NOT NULL THEN
      EXECUTE format('COMMENT ON INDEX connector.%I IS %L', final_name[k], note[k]);
    END IF;
  END LOOP;
END $$;
