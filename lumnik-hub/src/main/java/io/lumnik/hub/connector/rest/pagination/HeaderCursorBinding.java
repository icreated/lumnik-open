package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;

/** Cursor goes in an HTTP header (some legacy REST APIs). */
public final class HeaderCursorBinding implements CursorBinding {
    private final String headerName;

    public HeaderCursorBinding(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public RestRequest bind(RestRequest req, String value) {
        return req.withHeader(headerName, value);
    }
}
