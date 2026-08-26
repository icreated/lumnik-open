package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;

/** Default REST binding: cursor goes in the query string. */
public final class QueryParamCursorBinding implements CursorBinding {
    private final String paramName;

    public QueryParamCursorBinding(String paramName) {
        this.paramName = paramName;
    }

    @Override
    public RestRequest bind(RestRequest req, String value) {
        return req.withQuery(paramName, value);
    }
}
