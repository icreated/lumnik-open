package io.lumnik.extraction.spi.ask;

/**
 * Thrown when the asked scope has no schema to answer from — either none was ever discovered
 * (a predictable state: fresh install, no entity applied yet) or the stored card cannot be read
 * ({@link #unusable}). Both are owed an honest refusal at the API door, never an anonymous 500. Lives in the SPI so the caller
 * (the rag chat resource) can translate it without depending on the hub.
 */
public class NoSchemaException extends RuntimeException {

    private final String scope;

    public NoSchemaException(String scope) {
        this(scope, "no schema discovered for scope '" + scope
            + "' — apply an entity or run discovery first");
    }

    private NoSchemaException(String scope, String message) {
        super(message);
        this.scope = scope;
    }

    /**
     * The card EXISTS but cannot be read — a corrupt or hand-edited {@code connector.schema_card} row.
     * Same type, and deliberately so: the door already renders it as an honest 409 with the gesture
     * that fixes both causes, and the difference the asker needs is in the message, not in a second
     * exception the door would have to learn. Saying "no schema discovered" here would be a lie: there
     * is one, and it is unusable.
     */
    public static NoSchemaException unusable(String scope, String reason) {
        return new NoSchemaException(scope, "the stored schema card for scope '" + scope
            + "' cannot be read (" + reason + ") — re-run discovery, or apply the entity again, to "
            + "replace it");
    }

    public String scope() {
        return scope;
    }
}
