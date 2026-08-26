-- Five cell hooks answer a value they cannot parse by writing NULL into the cell and letting the
-- row through. The row is written, conservation holds, no dead letter is raised — so an installer
-- seeing an empty column had no way to tell a parse that gave up from a genuinely empty source.
-- The counter existed in MappingState all along and was read by nobody; this column is where it
-- finally lands. Historical runs start at 0, which is honest: nobody counted.

ALTER TABLE connector.connector_run ADD COLUMN IF NOT EXISTS cell_errors BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN connector.connector_run.cell_errors IS
    'Cells nulled by a parse hook (parse-date, parse-number, parse-email, parse-phone, lookup) that could not parse the source value and let the row through rather than dropping it. Counts CELLS, never rows, and is deliberately outside the conservation invariant records_in = records_out + records_skipped.';
