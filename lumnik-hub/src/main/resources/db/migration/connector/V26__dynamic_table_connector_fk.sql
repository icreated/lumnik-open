-- Dynamic-table registry rows must cascade when their connector is deleted (a
-- safety net behind the application-level purge). connector_id (added in V20) had
-- no FK, so rows leaked on delete. Clear rows already orphaned by past deletes so
-- the constraint can validate, then add it.

DELETE FROM connector.dynamic_table dt
 WHERE dt.connector_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM connector.connector c WHERE c.id = dt.connector_id);

ALTER TABLE connector.dynamic_table
  ADD CONSTRAINT dynamic_table_connector_fk
  FOREIGN KEY (connector_id) REFERENCES connector.connector(id) ON DELETE CASCADE;
