package io.lumnik.hub.connector.rest;

import java.util.Objects;

/** Small URL helpers shared by the REST/GraphQL request builders. */
public final class Urls {

    private Urls() {}

    /**
     * Joins {@code base} and {@code path} with exactly one slash between them: any trailing slashes on
     * {@code base} and any leading slashes on the (trimmed) {@code path} are collapsed, so neither
     * {@code https://hostpath} nor {@code https://host//path} can occur — even for a pathological
     * {@code path} like {@code "//items"}. A blank/null {@code path} yields the base (trailing slashes
     * stripped). {@code path} is not validated upstream, so this is the single point that normalizes
     * it; {@code base} must not be null.
     */
    public static String join(String base, String path) {
        Objects.requireNonNull(base, "base must not be null");
        String b = base.replaceFirst("/+$", "");           // drop any trailing slashes
        if (path == null || path.isBlank()) {
            return b;
        }
        return b + "/" + path.strip().replaceFirst("^/+", "");   // trim + exactly one separator
    }
}
