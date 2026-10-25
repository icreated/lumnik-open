package io.lumnik.hub.connector.rest.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The credential a source is approached with, and whose it is. {@code owner} is the connector the
 * credential authenticates, carried explicitly because a kind that keeps state between reads (oauth2's
 * token, #1393) must keep it per connector and per tenant — two connectors can share a token URL and a
 * client id with different secrets. Every production spec has one ({@code RestEndpointReader.authSpecFor}
 * requires it); the three-argument form, with none, serves the factories' own tests, and oauth2 refuses it.
 */
public record AuthSpec(String kind, Map<String, String> config, List<String> healthWarnings, Owner owner) {

    public AuthSpec(String kind, Map<String, String> config, List<String> healthWarnings) {
        this(kind, config, healthWarnings, null);
    }

    /** The connector a credential authenticates, in its tenant. */
    public record Owner(Long tenantId, UUID connectorId) {}
}
