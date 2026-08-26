package io.lumnik.extraction.spi.ask;

/**
 * Thrown by an {@link AskPort} when the asked scope has no discovered schema to answer
 * from — a predictable state (fresh install, no entity applied yet), owed an honest
 * refusal at the API door, never an anonymous 500. Lives in the SPI so the caller
 * (the rag chat resource) can translate it without depending on the hub.
 */
public class NoSchemaException extends RuntimeException {

    private final String scope;

    public NoSchemaException(String scope) {
        super("no schema discovered for scope '" + scope + "' — apply an entity or run discovery first");
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
