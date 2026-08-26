package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.rest.RestResponse;

/** Identity parser: the body is already JSON, parsed by {@link RestResponse#of}. */
public final class JsonBodyParser implements BodyParser {
    public static final JsonBodyParser INSTANCE = new JsonBodyParser();
    private JsonBodyParser() {}

    @Override
    public RestResponse reparse(RestResponse raw) {
        return raw;
    }
}
