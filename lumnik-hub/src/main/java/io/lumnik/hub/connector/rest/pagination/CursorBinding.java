package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;

/**
 * Strategy for how a pagination cursor value is injected into the next request.
 * REST default = query parameter. GraphQL future = body variable. Some legacy REST = header.
 */
public interface CursorBinding {
    RestRequest bind(RestRequest req, String value);
}
