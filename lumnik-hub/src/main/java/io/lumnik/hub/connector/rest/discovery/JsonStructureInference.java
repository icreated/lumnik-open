package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.naming.JsonPaths;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class JsonStructureInference {

    public enum PaginationKind { CURSOR, PAGE, OFFSET, LINK_HEADER, RELAY, NONE, UNKNOWN }

    private static final List<String> ITEMS_KEYS = List.of("data", "items", "results", "content", "edges", "nodes", "records", "list");
    private static final List<String> ID_KEYS    = List.of("id", "uuid", "_id", "key", "identifier");
    /**
     * Keys that say an answer is one page among others, beyond the five envelopes {@link #inferPagination} names
     * (DRF's next, JSON:API's links, FastAPI's pages, NestJS's meta, Sequelize's count, a Slice's last, the
     * Kubernetes metadata.continue). One of them present keeps the kind unknown: the integrator decides.
     */
    private static final List<String> PAGING_KEYS = List.of("next", "previous", "prev", "links", "_links", "meta",
        "metadata", "count", "total", "totalCount", "total_count", "totalElements", "page", "pages", "last", "cursor",
        "next_cursor", "nextCursor", "nextPageToken", "next_page_token", "@odata.nextLink");

    public Optional<String> inferItemsPath(JsonNode root) {
        if (root.isArray()) return isRecords(root) ? Optional.of("$") : Optional.empty();   // ["a","b"] holds no records
        for (String k : ITEMS_KEYS) if (root.has(k) && root.get(k).isArray() && isRecords(root.get(k))) return Optional.of("$." + k);
        if (root.has("data") && root.get("data").isObject()) {
            JsonNode d = root.get("data");
            for (String k : ITEMS_KEYS) if (d.has(k) && d.get(k).isArray() && isRecords(d.get(k))) return Optional.of("$.data." + k);
        }
        return onlyListOfObjects(root);
    }

    /** An array the engine can read as rows: empty, or holding objects. */
    private static boolean isRecords(JsonNode array) {
        return array.isEmpty() || array.get(0).isObject();
    }

    /**
     * No known key: the one field holding an array of objects, e.g. Spring PetClinic's {@code vetList} (#1650).
     * Two such fields are not guessed between.
     */
    private Optional<String> onlyListOfObjects(JsonNode root) {
        List<String> lists = new ArrayList<>();
        root.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isArray() && !v.isEmpty() && isRecords(v)) lists.add(JsonPaths.member(e.getKey()));
        });
        return lists.size() == 1 ? Optional.of(lists.get(0)) : Optional.empty();
    }

    public PaginationKind inferPagination(JsonNode root, Map<String,List<String>> headers) {
        if (hasLinkNext(headers)) return PaginationKind.LINK_HEADER;
        if (root.has("has_more") && root.has("data") && root.get("data").isArray()) return PaginationKind.CURSOR;
        if (hasPath(root, "data.x.pageInfo.endCursor") || containsPageInfo(root)) return PaginationKind.RELAY;
        if (root.has("totalPages") || root.has("hasNext")) return PaginationKind.PAGE;
        if (root.has("offset") && root.has("limit")) return PaginationKind.OFFSET;
        // The whole list at once (#1650) is a positive absence: a list found, and no paging signal at all.
        return inferItemsPath(root).isPresent() && !hasPagingSignal(root, headers) ? PaginationKind.NONE : PaginationKind.UNKNOWN;
    }

    private static boolean hasPagingSignal(JsonNode root, Map<String,List<String>> headers) {
        for (String k : PAGING_KEYS) if (root.has(k)) return true;
        for (String h : headers.keySet()) {
            String name = h.toLowerCase(Locale.ROOT);
            if (name.equals("link") || name.startsWith("x-total") || name.startsWith("x-next") || name.startsWith("x-page")) return true;
        }
        return false;
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
