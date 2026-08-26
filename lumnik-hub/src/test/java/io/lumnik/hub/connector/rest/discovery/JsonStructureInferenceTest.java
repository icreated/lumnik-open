package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonStructureInferenceTest {

    ObjectMapper M = new ObjectMapper();
    JsonStructureInference inf = new JsonStructureInference();

    @Test void detects_stripe_cursor() throws Exception {
        var root = M.readTree("{\"has_more\":true,\"data\":[{\"id\":\"ch_1\"}]}");
        assertEquals(JsonStructureInference.PaginationKind.CURSOR, inf.inferPagination(root, Map.of()));
    }

    @Test void detects_link_header() throws Exception {
        var root = M.readTree("[{\"id\":1}]");
        assertEquals(JsonStructureInference.PaginationKind.LINK_HEADER,
            inf.inferPagination(root, Map.of("Link", List.of("<u>;rel=\"next\""))));
    }

    @Test void detects_relay() throws Exception {
        var root = M.readTree("{\"data\":{\"x\":{\"pageInfo\":{\"endCursor\":\"c\"},\"nodes\":[]}}}");
        assertEquals(JsonStructureInference.PaginationKind.RELAY, inf.inferPagination(root, Map.of()));
    }

    @Test void finds_data_array() throws Exception {
        var root = M.readTree("{\"has_more\":true,\"data\":[{\"id\":\"a\"}]}");
        assertEquals(Optional.of("$.data"), inf.inferItemsPath(root));
    }

    @Test void infers_unique_key_id() throws Exception {
        var item = M.readTree("{\"id\":\"x\",\"name\":\"y\"}");
        assertEquals(Optional.of("$.id"), inf.inferUniqueKey(item));
    }

    /**
     * Promoted columns come from JSON field names — the "Modèle d'adaptation" case. The column NAME must be
     * a safe (and unique) SQL identifier or the ext.* DDL crashes; the PATH must still extract the original
     * key (bracket notation when the key isn't a clean dot-path).
     */
    @Test void promotes_sanitize_pathological_field_names() throws Exception {
        var item = M.readTree("{\"Modèle d'adaptation\":\"v1\",\"modele_d_adaptation\":\"v2\",\"id\":\"x\"}");
        var promotes = inf.inferPromotes(item);

        List<String> names = promotes.stream().map(p -> (String) p.get("name")).toList();
        for (String n : names) {
            assertTrue(n.matches("[a-z0-9_]+"), "unsafe column name: " + n);
        }
        assertEquals(names.size(), Set.copyOf(names).size(), "duplicate column names: " + names);

        // every path still resolves to its original value
        String json = M.writeValueAsString(item);
        for (var p : promotes) {
            Object val = com.jayway.jsonpath.JsonPath.read(json, (String) p.get("path"));
            assertNotNull(val, "path " + p.get("path") + " did not resolve");
        }
    }

    /**
     * The path must keep extracting even when the field name contains the quoting/escape characters
     * themselves — a single ", a ', both, or a backslash. Raw embedding produces an invalid JsonPath
     * (or points at the wrong key); the path builder must escape and use one consistent quote style.
     */
    @Test void promote_paths_survive_quotes_and_backslashes() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode item = M.createObjectNode();
        item.put("it's", "v_apostrophe");
        item.put("a\"b", "v_quote");
        item.put("a\"'b", "v_both");
        item.put("a\\b", "v_backslash");
        item.put("ok", "v_clean");

        var promotes = inf.inferPromotes(item);
        String json = M.writeValueAsString(item);

        List<Object> read = promotes.stream()
            .map(p -> com.jayway.jsonpath.JsonPath.<Object>read(json, (String) p.get("path")))
            .toList();
        assertEquals(
            Set.of("v_apostrophe", "v_quote", "v_both", "v_backslash", "v_clean"),
            Set.copyOf(read),
            "some paths were invalid or pointed at the wrong key: "
                + promotes.stream().map(p -> p.get("path")).toList());
    }
}
