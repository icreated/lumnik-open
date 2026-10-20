-- V73 — A dynamic table's row is unique per tenant (#1198).
-- A t_* table holds every tenant's rows but was UNIQUE (_row_hash), a hash of the content only:
-- a second tenant's identical row was silently not stored. Each such table becomes
-- UNIQUE (tenant_id, _row_hash), found by its shape so a table whose registration is gone is
-- covered too. The new key is weaker than the old one: no existing row can violate it. One index
-- build per table, every table locked until the migration commits. Idempotent.

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN
    SELECT con.conrelid::regclass AS rel, con.conname
      FROM pg_catalog.pg_constraint con
      JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
     WHERE con.contype = 'u'
       AND cardinality(con.conkey) = 1
       AND a.attname = '_row_hash'
       AND EXISTS (SELECT 1 FROM pg_catalog.pg_attribute ta
                    WHERE ta.attrelid = con.conrelid AND ta.attname = 'tenant_id' AND NOT ta.attisdropped)
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I, ADD UNIQUE (tenant_id, _row_hash)', t.rel, t.conname);
  END LOOP;
END $$;
