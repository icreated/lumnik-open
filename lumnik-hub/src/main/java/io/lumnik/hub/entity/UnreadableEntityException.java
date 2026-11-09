package io.lumnik.hub.entity;

/**
 * A stored Entity definition this version of the hub cannot read (written by another version, or damaged). The words of the
 * parser are kept out of the message: they quote the text they choked on.
 */
public class UnreadableEntityException extends RuntimeException {

    public UnreadableEntityException(String entity, Throwable cause) {
        super("the stored definition of Entity '" + entity + "' cannot be read", cause);
    }
}
