-- V72 — Register every connector already writing a shared table (#1186).
-- Before V71 a second connector writing a table under the same tenant and mapping had no
-- registration of its own. A table's registrations decide who may read it and when a delete may
-- drop it, so each connector whose rows a table holds gets one, found from its rows: by run on a
-- connector.t_* table, by connector_id on a hybrid one — the rule of
-- DynamicTableRegistry.lineage(). Reads each registered table once, so the first boot after the
-- upgrade takes as long as that scan. Idempotent.

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN
    SELECT d.table_schema, d.table_name,
           array_agg(a.attname::text) @> ARRAY['tenant_id', '_source', '_run_id'] AS by_run,
           array_agg(a.attname::text) @> ARRAY['tenant_id', 'connector_id'] AS by_connector
      FROM (SELECT DISTINCT table_schema, table_name FROM connector.dynamic_table) d
      JOIN pg_catalog.pg_attribute a
        ON a.attrelid = to_regclass(format('%I.%I', d.table_schema, d.table_name)) AND NOT a.attisdropped
     GROUP BY d.table_schema, d.table_name
  LOOP
    IF t.by_run THEN
      EXECUTE format(
        'INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) '
        || 'SELECT d.tenant_id, d.mapping_id, d.table_schema, d.table_name, d.schema_inferred, r.connector_id '
        || 'FROM (SELECT DISTINCT tenant_id, _source, _run_id FROM %1$I.%2$I) w '
        || 'JOIN connector.connector_run r ON r.id = w._run_id AND r.tenant_id = w.tenant_id '
        || 'JOIN connector.dynamic_table d ON d.tenant_id = w.tenant_id AND d.mapping_id = w._source '
        || 'AND d.table_schema = %1$L AND d.table_name = %2$L '
        || 'ON CONFLICT DO NOTHING',
        t.table_schema, t.table_name);
    ELSIF t.by_connector THEN
      EXECUTE format(
        'INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) '
        || 'SELECT d.tenant_id, d.mapping_id, d.table_schema, d.table_name, d.schema_inferred, w.connector_id '
        || 'FROM (SELECT DISTINCT tenant_id, connector_id FROM %1$I.%2$I) w '
        || 'JOIN connector.connector c ON c.id = w.connector_id AND c.tenant_id = w.tenant_id '
        || 'JOIN connector.dynamic_table d ON d.tenant_id = w.tenant_id '
        || 'AND d.table_schema = %1$L AND d.table_name = %2$L '
        || 'ON CONFLICT DO NOTHING',
        t.table_schema, t.table_name);
    END IF;
  END LOOP;
END $$;

-- A stored schema card keeps a table only while every connector writing it carries the card's
-- scope — the rule RegistrySchemaBuilder now builds cards by, applied to the cards built before it.
UPDATE connector.schema_card sc
   SET card = jsonb_set(sc.card, '{tables}', (
         SELECT coalesce(jsonb_agg(e.tbl ORDER BY e.pos), '[]'::jsonb)
           FROM jsonb_array_elements(sc.card -> 'tables') WITH ORDINALITY AS e(tbl, pos)
          WHERE NOT EXISTS (
                SELECT 1 FROM connector.dynamic_table o
                  LEFT JOIN connector.connector oc ON oc.id = o.connector_id AND oc.tenant_id = o.tenant_id
                 WHERE o.tenant_id = sc.tenant_id
                   AND o.table_schema || '.' || o.table_name = e.tbl ->> 'name'
                   AND (sc.scope = ANY(oc.tags)) IS NOT TRUE))),
       updated_at = now()
 WHERE jsonb_typeof(sc.card -> 'tables') = 'array';
