package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Urls;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Shared bits the pagination strategies all need: extracting the item list from a response body via
 * {@code response_path}, and building the first GET to {@code base_url + path}. Keeps the strategies
 * free of the boilerplate they otherwise copy verbatim.
 */
public final class PaginationSupport {

    private PaginationSupport() {}

    /** The rows at {@code responsePath} as a list of maps; empty when the path is absent or not a list. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(JsonNode json, String responsePath) {
        Object items = JsonPathExtractor.read(json, responsePath);
        return items instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
    }

    /**
     * The row count a source publishes at {@code totalPath} — the witness a run is measured against
     * once its pagination ends. Empty when the manifest names no path, and empty when the path is
     * there but the body does not carry it: an absent count read as 0 would refuse every run.
     *
     * <p>Spelled once because three strategies consume it (offset, page, body-link) and a rule
     * written three times is a rule that can disagree with itself.
     */
    public static OptionalLong declaredItems(JsonNode json, String totalPath) {
        // Blank counts as unset, the way an optional path key already does one class over
        // (PagePaginationStrategy's size_param). A templated manifest or a YAML default leaves
        // `total_path: ""` behind meaning "none named"; compiling it instead throws and fails every
        // run of an endpoint whose only sin is an empty optional field.
        if (optional(totalPath) == null) return OptionalLong.empty();
        Long total = number(json, totalPath, "total_path");
        return total == null ? OptionalLong.empty() : OptionalLong.of(total);
    }

    /**
     * The row count a source publishes, from the response header {@code totalHeader} when one is named (#1632: Gitea,
     * GitLab, WordPress put it there), else from the body at {@code totalPath}. A header the response does not carry
     * is no witness; one that is not a row count (text, a negative) is refused by its key, never its value.
     */
    public static OptionalLong declaredItems(RestResponse resp, String totalPath, String totalHeader) {
        if (optional(totalHeader) == null) return declaredItems(resp.json(), totalPath);
        String text = resp.header(totalHeader).map(String::strip).orElse(null);
        if (text == null || text.isEmpty()) return OptionalLong.empty();
        if (!ROW_COUNT.matcher(text).matches()) {
            throw new ConnectorException("pagination.total_header must read a row count: a whole number from 0");
        }
        return OptionalLong.of(Long.parseLong(text));
    }

    /** A row count: never negative, never an exponent to expand (#1090). */
    private static final Pattern ROW_COUNT = Pattern.compile("\\d{1,18}");

    /**
     * A pagination flag at {@code path}: {@code true}, {@code false}, or {@code null} when the body does not carry it.
     * Anything else is refused by its key and its JSON type, never its value — the value may be a token. SOAP reads
     * its flags leniently instead ({@code SoapPaginationStrategy}): XML carries no types.
     */
    static Boolean flag(JsonNode json, String path, String key) {
        Object v = one(JsonPathExtractor.read(json, path), key);
        if (v == null || v instanceof Boolean) return (Boolean) v;
        throw new ConnectorException("pagination." + key + " must read a boolean, not a " + jsonType(v));
    }

    /**
     * The value that carries the read to the next page — a cursor, a next link — or {@code null} when there is none:
     * absent, blank, or an empty slice ({@code $.data[-1:].id} on an empty page).
     */
    static String next(JsonNode json, String path, String key) {
        Object v = one(JsonPathExtractor.read(json, path), key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    /**
     * The next page's URL at {@code path} (body-link), or {@code null} on the last page. A link is a string, or a link
     * object carrying it as {@code href} — JSON:API allows both, Drupal writes the object, HAL always does (#1638).
     * Anything else is refused by its key and JSON type, never its value: turned into text, a map became {@code {href=…}}.
     */
    static String nextLink(JsonNode json, String path) {
        Object v = one(JsonPathExtractor.read(json, path), "link_path");
        if (v instanceof Map<?, ?> link && link.containsKey("href")
                && (link.get("href") == null || link.get("href") instanceof CharSequence)) {
            v = link.get("href");
        }
        if (v == null || v instanceof CharSequence) return v == null || v.toString().isBlank() ? null : v.toString();
        String type = jsonType(v);
        throw new ConnectorException("pagination.link_path must read a URL or a link object with href, not "
            + ("aeiou".indexOf(type.charAt(0)) >= 0 ? "an " : "a ") + type);
    }

    /**
     * A count at {@code path} — a number, or the digits of one sent as text, as legacy APIs do — or {@code null} when
     * the body does not carry it. Anything else is refused by its key and JSON type, never its value.
     */
    public static Long number(JsonNode json, String path, String key) {
        Object v = one(JsonPathExtractor.read(json, path), key);
        if (v == null || v instanceof Number) return v == null ? null : ((Number) v).longValue();
        if (v instanceof CharSequence text && text.toString().strip().matches("-?\\d{1,18}")) {
            return Long.parseLong(text.toString().strip());
        }
        throw new ConnectorException("pagination." + key + " must read a number, not a " + jsonType(v));
    }

    /**
     * One value from a path: a list counts only when it holds at most one — a slice like {@code [-1:]}. Several
     * values (a deep scan reaching nested connections, a filter) are refused rather than one picked: a nested
     * cursor followed past the outer end reads pages that are not this endpoint's.
     */
    private static Object one(Object v, String key) {
        if (!(v instanceof List<?> l)) return v;
        if (l.size() > 1) throw new ConnectorException("pagination." + key + " must read one value, not " + l.size());
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * The cursor of a page whose flag said "more" (cursor and relay): one is owed, so none is refused — stopping
     * there would end the run complete with the rest of the source never read.
     */
    static String cursorWhileMore(JsonNode json, String cursorPath) {
        String cursor = next(json, cursorPath, "cursor_path");
        if (cursor == null) throw new ConnectorException("pagination.has_more_path says more pages, but cursor_path reads no cursor");
        return cursor;
    }

    /** An optional config value: a blank one means none, as an absent one does (a templated manifest leaves {@code ""}). */
    public static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String jsonType(Object v) {
        return switch (v) {
            case CharSequence s -> "string";
            case Number n -> "number";
            case List<?> l -> "list";
            case Map<?, ?> m -> "object";
            default -> v.getClass().getSimpleName();
        };
    }

    /** The first request: a GET to {@code base_url + path} (one-slash join), or the null-cfg sentinel. */
    public static RestRequest baseGet(EndpointConfig cfg) {
        return cfg == null ? RestRequest.get("about:blank")
            : RestRequest.get(Urls.join(cfg.baseUrl(), cfg.path()));
    }
}
