package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jayway.jsonpath.Configuration;
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
        JsonPath jp = cache.get(path, JsonPath::compile);
        return (T) JsonPath.using(CFG).parse(root.toString()).read(jp, Object.class);
    }
}
