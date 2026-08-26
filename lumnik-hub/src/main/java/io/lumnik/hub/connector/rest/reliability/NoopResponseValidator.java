package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.rest.RestResponse;

/** Default for REST: HTTP status code is the only signal. */
public final class NoopResponseValidator implements ResponseValidator {
    @Override
    public void validate(RestResponse response) { /* no-op */ }
}
