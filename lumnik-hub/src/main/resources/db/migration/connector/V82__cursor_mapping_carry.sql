-- V82 (#1452): what a stateful mapping hook (fill-down) carries from one chunk of a file to the next, written in
-- the chunk's transaction with the position it stands for, so a resume starts from exactly that state.
-- NULL or JSON null: nothing carried. Deleted with the row (lm endpoint reset-cursor, a refused end of stream).
ALTER TABLE connector.connector_cursor ADD COLUMN mapping_carry jsonb;
