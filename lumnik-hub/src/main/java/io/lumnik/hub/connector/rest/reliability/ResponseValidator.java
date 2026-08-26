package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.RestResponse;

/**
 * Inspect a successful HTTP response for semantic errors that the HTTP status code doesn't reveal.
 * REST default = no-op (HTTP status suffices). GraphQL future = inspect $.errors[]. SOAP would inspect Fault.
 */
public interface ResponseValidator {
    /**
     * @throws TransientFailureException to trigger a retry (treated like a 5xx)
     * @throws RuntimeException for permanent failures (no retry; the page fails)
     */
    void validate(RestResponse response);
}
