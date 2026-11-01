package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;

/**
 * What every request to a source goes through — a run's pages, the pre-flight, the probe: the connector's credential,
 * already checked, and the client that trusts the connector's CA (#1609). Opened by {@link RestEndpointReader#connect},
 * one place and one order, so a caller cannot sign with one and forget the other.
 */
public record RestConnection(AuthProvider auth, RestHttpClient client) {

    /** Sends {@code unsigned}, signed by the credential, through {@code sender} — the caller's rate limit and retries. */
    public RestResponse send(RestRequest unsigned, AuthProvider.Sender sender) throws Exception {
        return auth.send(unsigned, sender);
    }
}
