package io.lumnik.hub.entity;

/**
 * An Entity reads, as a source or a lookup, the view of an Entity that is not applied (#2042): the view does not exist
 * yet, and its scopes, which bound who may read this one, are unknown. The door answers 409 with the gesture.
 */
public class SourceEntityNotAppliedException extends RuntimeException {

    public SourceEntityNotAppliedException(String role, String table, String entity) {
        super(role + " table " + table + " is entity " + entity + "'s view, and no entity " + entity
            + " is applied: apply entity " + entity + " first, then this one");
    }
}
