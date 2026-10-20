-- V75__search_index_withheld.sql — a re-apply that removes a field withholds the index until rebuilt (#1219).
--
-- The live build was made from an earlier declaration and still serves what a re-apply removed, so the search
-- door leaves a withheld index out. Set by the apply door (also for a name whose purge is still pending),
-- cleared by the projection worker once a rebuild of the current declaration has cut over.
ALTER TABLE core.search_index ADD COLUMN withheld BOOLEAN NOT NULL DEFAULT false;
