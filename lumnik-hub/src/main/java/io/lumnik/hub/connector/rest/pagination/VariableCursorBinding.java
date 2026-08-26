package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.rest.RestRequest;

/**
 * GraphQL cursor binding: the relay cursor is written into the POST body's
 * {@code variables.<name>} instead of a query parameter. Used when a relay
 * pagination block declares {@code cursor_binding: variable}.
 */
public final class VariableCursorBinding implements CursorBinding {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String VARIABLES = "variables";

    private final String variableName;

    public VariableCursorBinding(String variableName) {
        this.variableName = variableName;
    }

    @Override
    public RestRequest bind(RestRequest req, String value) {
        try {
            JsonNode parsed = (req.body() == null || req.body().isBlank())
                ? M.createObjectNode()
                : M.readTree(req.body());
            // Explicit check for a clearer message than a raw ClassCastException on the cast below.
            if (!parsed.isObject()) {
                throw new IllegalStateException("request body is not a JSON object (" + parsed.getNodeType() + ")");
            }
            ObjectNode root = (ObjectNode) parsed;
            ObjectNode vars = (root.has(VARIABLES) && root.get(VARIABLES).isObject())
                ? (ObjectNode) root.get(VARIABLES)
                : root.putObject(VARIABLES);
            vars.put(variableName, value);
            return req.withBody(M.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject GraphQL cursor variable '" + variableName + "'", e);
        }
    }
}
