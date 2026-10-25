package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

public interface AuthProvider {
    /** Signs a request. A request to a source goes through {@link #send}, which may sign it again. */
    RestRequest apply(RestRequest request);
    default void probe() {}

    /**
     * Sends {@code unsigned}, signed, through {@code sender} — the one path every request to a source takes, so a
     * kind whose credential can go stale replays it where it is signed: oauth2 re-signs the ORIGINAL request after
     * a 401, never the one carrying the refused token (#1393). The other kinds sign once.
     */
    default RestResponse send(RestRequest unsigned, Sender sender) throws Exception {
        return sender.send(apply(unsigned));
    }

    /** What sends a signed request: the rate limit and the retries of the caller, or the bare client. */
    @FunctionalInterface
    interface Sender {
        RestResponse send(RestRequest signed) throws Exception;
    }
}
