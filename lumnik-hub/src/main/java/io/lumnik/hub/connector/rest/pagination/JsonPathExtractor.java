package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonPathExtractor {

    private static final Configuration CFG =
        Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL);

    private static final Map<String, JsonPath> cache = new ConcurrentHashMap<>();

    private JsonPathExtractor() {}

    @SuppressWarnings("unchecked")
    public static <T> T read(JsonNode root, String path) {
        if (path == null) return null;
        JsonPath jp = cache.computeIfAbsent(path, JsonPath::compile);
        return (T) JsonPath.using(CFG).parse(root.toString()).read(jp, Object.class);
    }
}
