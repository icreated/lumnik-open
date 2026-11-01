package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;

/**
 * The body is already JSON, parsed by {@link RestResponse#of} — which stands an empty object in for a body it
 * could not parse. Such a body is refused here (#1644): an XML answer, or an HTML login page served with a 200,
 * read as {@code {}} would run Completed with no rows. The refusal names the Content-Type, never the body.
 */
public final class JsonBodyParser implements BodyParser {
    public static final JsonBodyParser INSTANCE = new JsonBodyParser();
    private JsonBodyParser() {}

    @Override
    public RestResponse reparse(RestResponse raw) {
        String body = raw.body();
        boolean emptyObject = raw.json().isObject() && raw.json().isEmpty();
        if (body == null || body.isBlank() || !emptyObject || isLiterallyEmptyObject(body)) return raw;
        String type = raw.header("Content-Type").map(t -> "Content-Type " + t).orElse("no Content-Type");
        throw new ConnectorException("the response is not JSON (" + type + ") — rest-generic reads JSON");
    }

    /** {@code {}}, spaces allowed: the only body that parses to an empty object; anything else read so was the stand-in. */
    private static boolean isLiterallyEmptyObject(String body) {
        String s = body.strip();
        return s.length() >= 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}' && s.substring(1, s.length() - 1).isBlank();
    }
}
