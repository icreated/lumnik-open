package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.rest.RestResponse;

/**
 * Turns a raw HTTP response into one whose {@link RestResponse#json()} is the tree the pagination
 * strategies read. REST/GraphQL bodies are already JSON ({@link JsonBodyParser} = identity); SOAP
 * bodies are XML ({@link io.lumnik.hub.connector.rest.soap.XmlBodyParser}). This is the Open/Closed
 * seam that makes "parse the body" an explicit, per-request-shape choice.
 */
public interface BodyParser {
    RestResponse reparse(RestResponse raw);
}
