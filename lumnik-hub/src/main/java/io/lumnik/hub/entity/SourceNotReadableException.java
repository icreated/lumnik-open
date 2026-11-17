package io.lumnik.hub.entity;

/**
 * A caller bound to scopes names, as a source or a lookup, a table that does not stay within its writers' scopes
 * (#2048): a connector of the tenant writing it lacks a scope the Entity declares, or none of them writes it (whether
 * the table exists or not). The door answers the uniform 403 and logs this message.
 */
public class SourceNotReadableException extends RuntimeException {

    public SourceNotReadableException(String role, String table) {
        super(role + " table " + table + " is not written within the entity's scopes by the tenant's connectors");
    }
}
