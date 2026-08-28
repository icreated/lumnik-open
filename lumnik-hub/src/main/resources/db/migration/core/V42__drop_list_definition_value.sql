-- V42: Drop core.list_definition / core.list_value — business value lists nothing ever read.
-- The V7 pair was reachable only through ListResource's own CRUD: no ingestion, mapping, ask,
-- view or workflow path ever consulted a value, and no enum in the repository carried the
-- @ListMirror annotation the bootstrapper scanned for. What the pair was reaching for now
-- exists for real in kind:Workflow (core.workflow_manifest, V32): declared states, aliases for
-- dirty legacy values, transitions — read by the sweep and indexed into the RAG corpus.
-- The entities, repositories, DTOs, REST resource, bootstrapper, annotation and the three
-- list.value.* event descriptors are removed with this migration.

DROP TABLE IF EXISTS core.list_value;
DROP TABLE IF EXISTS core.list_definition;

DROP SEQUENCE IF EXISTS core.list_value_id_seq;
DROP SEQUENCE IF EXISTS core.list_definition_id_seq;
