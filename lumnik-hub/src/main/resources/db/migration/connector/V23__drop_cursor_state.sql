-- V23: Drop connector.cursor_state — the legacy REST direct path is retired.
-- The REST connector now runs exclusively through the engine path (RestEndpointReader/Writer), whose
-- incremental cursor lives in connector.connector_cursor (the single source of truth). cursor_state
-- (V13) was written only by the now-deleted RestConnectorRuntime/CursorTracker. DROP TABLE also
-- removes its RLS policy (V13) and grants — no separate cleanup needed.

DROP TABLE IF EXISTS connector.cursor_state;
