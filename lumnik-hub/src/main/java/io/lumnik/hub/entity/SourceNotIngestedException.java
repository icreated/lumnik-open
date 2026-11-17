package io.lumnik.hub.entity;

/**
 * An Entity names, as a source or a lookup, a table no connector writes (#2003): one of the hub's own tables under
 * {@code connector} (connector_credential, entity_manifest, saved_view…), whose rows the view would hand to every
 * {@code lm_user} of the tenant and to the chat. Refused before anything is compiled; the door answers 400.
 */
public class SourceNotIngestedException extends RuntimeException {

    public SourceNotIngestedException(String role, String table) {
        super(role + " table " + table + " is not a table a connector writes: an Entity fuses connector.t_<name> tables, "
            + "the REST targets the hub made under connector, and other Entities' views");
    }
}
