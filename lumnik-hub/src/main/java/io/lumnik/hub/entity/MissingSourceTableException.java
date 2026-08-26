package io.lumnik.hub.entity;

/**
 * The entity's CREATE VIEW named a source table that does not exist — a predictable
 * state on a fresh install (the source connectors were never run), owed an honest
 * refusal at the API door, never an anonymous 500. Carries the database's own first
 * line ("relation \"connector.x\" does not exist") so the refusal names the culprit.
 */
public class MissingSourceTableException extends RuntimeException {

    public MissingSourceTableException(String dbMessage) {
        super("source " + dbMessage
            + " — the entity fuses tables its source connectors create; "
            + "run the source connectors first, then apply again");
    }
}
