package io.lumnik.hub.connector.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;

/**
 * A source behind an internal CA (#1609): the public trust fails the handshake, the client trusting its CA answers
 * {@code body}. Records the PEMs it was asked to trust and the last request that reached it.
 */
public final class InternalCaApi extends RestHttpClient {
    public final List<String> trustedPems = new ArrayList<>();
    public RestRequest sent;
    private final String body;

    public InternalCaApi(String body) { this.body = body; }

    @Override public RestResponse execute(RestRequest req) throws Exception {
        throw new SSLHandshakeException("PKIX path building failed");
    }

    @Override public RestHttpClient trusting(String caPem) {
        trustedPems.add(caPem);
        return new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) {
                sent = req;
                return RestResponse.of(200, Map.of(), body);
            }
        };
    }
}
