package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.naming.JsonPaths;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class JsonStructureInference {

    public enum PaginationKind { CURSOR, PAGE, OFFSET, LINK_HEADER, RELAY, UNKNOWN }

    private static final List<String> ITEMS_KEYS = List.of("data", "items", "results", "content", "edges", "nodes", "records", "list");
    private static final List<String> ID_KEYS    = List.of("id", "uuid", "_id", "key", "identifier");

    public Optional<String> inferItemsPath(JsonNode root) {
        if (root.isArray()) return Optional.of("$");
        for (String k : ITEMS_KEYS) if (root.has(k) && root.get(k).isArray()) return Optional.of("$." + k);
        if (root.has("data") && root.get("data").isObject()) {
            JsonNode d = root.get("data");
            for (String k : ITEMS_KEYS) if (d.has(k) && d.get(k).isArray()) return Optional.of("$.data." + k);
        }
        return Optional.empty();
    }

    public PaginationKind inferPagination(JsonNode root, Map<String,List<String>> headers) {
        if (hasLinkNext(headers)) return PaginationKind.LINK_HEADER;
        if (root.has("has_more") && root.has("data") && root.get("data").isArray()) return PaginationKind.CURSOR;
        if (hasPath(root, "data.x.pageInfo.endCursor") || containsPageInfo(root)) return PaginationKind.RELAY;
        if (root.has("totalPages") || root.has("hasNext")) return PaginationKind.PAGE;
        if (root.has("offset") && root.has("limit")) return PaginationKind.OFFSET;
        return PaginationKind.UNKNOWN;
    }

    /** True when any {@code Link} header advertises a {@code rel="next"} relation. */
    private boolean hasLinkNext(Map<String,List<String>> headers) {
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Link")) {
                for (String v : e.getValue()) {
                    if (v.contains("rel=\"next\"")) return true;
                }
            }
        }
        return false;
    }

    public Optional<String> inferUniqueKey(JsonNode item) {
        for (String k : ID_KEYS) if (item.has(k)) return Optional.of("$." + k);
        return Optional.empty();
    }

    public List<Map<String,Object>> inferPromotes(JsonNode item) {
        List<Map<String,Object>> out = new ArrayList<>();
        Identifiers.Unique columns = new Identifiers.Unique();
        item.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isValueNode()) {
                String type = v.isInt() || v.isLong() ? "bigint"
                            : v.isFloatingPointNumber() ? "decimal"
                            : v.isBoolean() ? "boolean"
                            : "text";
                // Column name → safe, unique SQL identifier; path keeps the ORIGINAL key (bracket
                // notation when it isn't a clean dot-path) so the value still extracts.
                String name = columns.take(Identifiers.slug(e.getKey(), "col"));
                out.add(Map.of("name", name, "path", JsonPaths.member(e.getKey()), "type", type));
            }
        });
        return out;
    }

    private boolean hasPath(JsonNode root, String dottedPath) {
        JsonNode n = root;
        for (String seg : dottedPath.split("\\.")) { n = n.path(seg); if (n.isMissingNode()) return false; }
        return true;
    }

    private boolean containsPageInfo(JsonNode n) {
        if (n.isObject() && n.has("pageInfo")) return true;
        for (JsonNode child : n) if (containsPageInfo(child)) return true;
        return false;
    }
}
