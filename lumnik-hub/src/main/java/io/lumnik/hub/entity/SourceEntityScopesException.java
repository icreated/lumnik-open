package io.lumnik.hub.entity;

import java.util.List;

/**
 * An Entity reads another Entity's view and declares a scope that one does not carry (#2042). Entity reads ask
 * {@code allowsAny}, so that scope's holders would read the other Entity's rows through this one. The door answers 400;
 * a caller who may not read the other Entity gets {@link SourceEntityNotAppliedException} instead.
 */
public class SourceEntityScopesException extends RuntimeException {

    public SourceEntityScopesException(String role, String table, String entity, List<String> held, List<String> declared) {
        super(role + " table " + table + " is entity " + entity + "'s view, scoped " + held + ": this entity's scopes "
            + declared + " must be among them, or its readers would read " + entity + " without holding its scopes");
    }
}
