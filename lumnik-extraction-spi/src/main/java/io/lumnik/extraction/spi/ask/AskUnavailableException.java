package io.lumnik.extraction.spi.ask;

/**
 * Analytic Ask is switched off because its security boundary is not guaranteed: the database role the model's SQL
 * runs under can still change its tenant (#1987). A deployment state, like a model that is not running — owed a 503
 * at the door, never an answer. The message names the state, not the privileges: those are for the operator's log.
 * Lives in the SPI so the chat door can translate it without depending on the hub.
 */
public class AskUnavailableException extends RuntimeException {

    public AskUnavailableException() {
        super("analytic ask is unavailable: the readonly database role is not hardened");
    }

    /** Another deployment state that closes Ask, named by its message (#2005: the readonly connection as configured). */
    protected AskUnavailableException(String message) {
        super(message);
    }
}
