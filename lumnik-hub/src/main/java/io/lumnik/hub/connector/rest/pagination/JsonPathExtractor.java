package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;


public final class JsonPathExtractor {

    private static final Configuration CFG =
        Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL);

    // Bounded: path expressions come from connector manifests, but nothing should be
    // able to grow a static map without limit.
    private static final Cache<String, JsonPath> cache =
        Caffeine.newBuilder().maximumSize(1_000).build();

    private JsonPathExtractor() {}

    @SuppressWarnings("unchecked")
    public static <T> T read(JsonNode root, String path) {
        if (path == null) return null;
        return readParsed(root.toString(), path);
    }

    /**
     * The same read over a document json-smart already produced (an item a pagination strategy
     * handed over) — or its text. The tree route serialises and parses it back; this one reads it as
     * it is. Not for a {@code JsonNode}: json-smart would take it for a bean, so it goes through
     * {@link #read(JsonNode, String)}.
     */
    @SuppressWarnings("unchecked")
    public static <T> T readParsed(Object document, String path) {
        if (path == null) return null;
        JsonPath jp = cache.get(path, JsonPath::compile);
        DocumentContext ctx = document instanceof String json
            ? JsonPath.using(CFG).parse(json)
            : JsonPath.using(CFG).parse(document);
        return (T) ctx.read(jp, Object.class);
    }
}
