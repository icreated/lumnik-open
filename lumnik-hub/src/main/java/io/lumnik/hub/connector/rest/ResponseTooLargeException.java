package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.exceptions.ConnectorException;

/** A response past the hub's cap (#2002, {@code lumnik.outbound.max-response-mb}): never retried, named in our words. */
public class ResponseTooLargeException extends ConnectorException {
    public ResponseTooLargeException(String message) { super(message); }
}
