package io.lumnik.hub.connector.naming;

/**
 * Builds JsonPath expressions that address a single object member by its <em>literal</em> key — even
 * when the key is not a clean dot-path token.
 *
 * <p>Naive {@code "$." + key} silently mis-reads keys containing JsonPath metacharacters: a CSV header
 * {@code order.id} becomes {@code $.order.id} (a nested read that returns nothing → silent data loss),
 * and spaces / quotes / brackets produce invalid paths. We emit dot notation only for safe tokens and
 * otherwise single-quoted bracket notation with the key escaped (backslash first, then the quote), so
 * any character yields a valid path pointing at the right key.
 */
public final class JsonPaths {

    private JsonPaths() {}

    public static String member(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("member key required");
        }
        if (key.matches("[A-Za-z_]\\w*")) {
            return "$." + key;
        }
        String escaped = key.replace("\\", "\\\\").replace("'", "\\'");
        return "$['" + escaped + "']";
    }
}
