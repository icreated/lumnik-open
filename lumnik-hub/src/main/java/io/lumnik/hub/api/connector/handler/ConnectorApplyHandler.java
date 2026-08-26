package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;

public interface ConnectorApplyHandler {

    /** Connector kind this handler persists, e.g. "rest-generic". */
    String kind();

    /** Persist the manifest. Caller guarantees validation already passed. */
    ApplyResult apply(JsonNode manifest, long tenant);
}
