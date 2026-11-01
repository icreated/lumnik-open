package io.lumnik.hub.entity;

/**
 * The entity's CREATE VIEW named a source table that does not exist — a predictable
 * state on a fresh install (the source connectors were never run), owed an honest
 * refusal at the API door, never an anonymous 500. Carries the database's own first
 * line ("relation \"connector.x\" does not exist") so the refusal names the culprit.
 */
public class MissingSourceTableException extends RuntimeException {

    public MissingSourceTableException(String dbMessage) {
        this(dbMessage, null);
    }

    /** With the failure it translates, so a cleanup failure suppressed on it stays reachable down its cause chain (#1607). */
    public MissingSourceTableException(String dbMessage, Throwable cause) {
        super("source " + dbMessage
            + " — the entity fuses tables its source connectors create; "
            + "run the source connectors first, then apply again", cause);
    }
}
