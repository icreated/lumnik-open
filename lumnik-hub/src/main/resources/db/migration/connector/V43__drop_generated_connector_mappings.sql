-- V43 — Remove the per-connector mapping rows the guided jdbc add used to write.
--
-- `lm source jdbc add` persisted one connector.connector_mapping row per table, named
-- "<connector>-mapping" with spec_format 'yaml', and pointed connector.mapping_id at it. Nothing
-- ever read it back: the MappingResolver bound into every ConnectorContext is an identity stub,
-- the mapping a jdbc run executes is built from the connector's own config
-- (JdbcConnectorType.dumpSpec), and connector.dynamic_table keys its schema on the spec id string
-- ("jdbc:<table>"), not on this row.
--
-- What the row did have was UNIQUE (tenant_id, name). `connector delete` does not remove it —
-- mapping_id is a soft ref with no FK, declared that way in V9 for an independent lifecycle — so a
-- deleted table could never be added again: the second add's INSERT failed at COMMIT, after the
-- door had already answered 200, and no command could remove the orphan.
--
-- The predicate is the guided add's whole signature, not merely "template_id IS NULL": the table
-- also holds the per-tenant template overrides MappingResolverImpl reads (those carry a
-- template_id), and a DELETE is irreversible, so it matches only what that one door wrote — a
-- NULL template_id, the '-mapping' suffix it appended, and the 'yaml' format it stamped. A row
-- shaped otherwise is left alone even though no reader remains for it.
--
-- The UPDATE and the DELETE carry the SAME predicate on purpose: clearing mapping_id for a row
-- that then survives would break a link this migration had no business touching.

UPDATE connector.connector c
   SET mapping_id = NULL
  FROM connector.connector_mapping m
 WHERE c.mapping_id = m.id
   AND m.template_id IS NULL
   AND m.spec_format = 'yaml'
   AND m.name LIKE '%-mapping';

DELETE FROM connector.connector_mapping
 WHERE template_id IS NULL
   AND spec_format = 'yaml'
   AND name LIKE '%-mapping';
