package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
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

    /** #1650: Spring PetClinic's {"vetList": [...]} — no known key, one array of objects: that is the list. */
    @Test void finds_the_one_array_of_objects_under_an_unknown_key() throws Exception {
        var root = M.readTree("{\"vetList\":[{\"id\":1}],\"generatedAt\":\"2026-10-31\",\"tags\":[\"a\",\"b\"]}");
        assertEquals(Optional.of("$.vetList"), inf.inferItemsPath(root));
    }

    @Test void two_arrays_of_objects_under_unknown_keys_are_not_guessed_between() throws Exception {
        var root = M.readTree("{\"vets\":[{\"id\":1}],\"owners\":[{\"id\":2}]}");
        assertEquals(Optional.empty(), inf.inferItemsPath(root));
    }

    @Test void a_list_key_that_needs_brackets_is_written_as_a_path_that_reads_it() throws Exception {
        var root = M.readTree("{\"vet list\":[{\"id\":1}]}");
        String path = inf.inferItemsPath(root).orElseThrow();
        assertEquals(1, ((List<?>) JsonPath.read(M.writeValueAsString(root), path)).size());
    }

    /** #1650: the whole list at once is a positive absence — a list found, and no paging signal at all. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"vetList\":[{\"id\":1}]}",                                   // Spring PetClinic of before Boot
        "[{\"id\":1},{\"id\":2}]"                                        // a bare array, no Link header
    })
    void a_list_with_no_paging_signal_is_the_whole_list(String body) throws Exception {
        assertEquals(JsonStructureInference.PaginationKind.NONE, inf.inferPagination(M.readTree(body), Map.of()));
    }

    /** Envelopes the five detectors do not name are paged all the same: they stay unknown, never none. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"count\":120,\"next\":\"http://x/?page=2\",\"previous\":null,\"results\":[{\"id\":1}]}",   // DRF
        "{\"data\":[{\"id\":\"1\"}],\"links\":{\"next\":\"http://x/?page[offset]=10\"}}",          // JSON:API
        "{\"items\":[{\"id\":1}],\"total\":120,\"page\":1,\"size\":50,\"pages\":3}",               // FastAPI
        "{\"data\":[{\"id\":1}],\"meta\":{\"currentPage\":1}}",                                  // NestJS
        "{\"count\":120,\"rows\":[{\"id\":1}]}",                                                // Sequelize
        "{\"content\":[{\"id\":1}],\"last\":false,\"number\":0}",                                // Spring Slice
        "{\"items\":[{\"id\":1}],\"metadata\":{\"continue\":\"eyJ2\"}}"                             // Kubernetes
    })
    void a_paged_envelope_the_detectors_do_not_name_stays_unknown(String body) throws Exception {
        assertEquals(JsonStructureInference.PaginationKind.UNKNOWN, inf.inferPagination(M.readTree(body), Map.of()));
    }

    @Test void a_top_level_array_of_scalars_is_no_list_of_records() throws Exception {
        assertEquals(Optional.empty(), inf.inferItemsPath(M.readTree("[\"a\",\"b\"]")));
        assertEquals(Optional.of("$"), inf.inferItemsPath(M.readTree("[]")));
    }

    /** The same rule under a known key: {"data": ["a","b"]} holds no records, so it is never the whole list. */
    @Test void a_known_key_holding_scalars_is_no_list_of_records() throws Exception {
        var root = M.readTree("{\"data\":[\"a\",\"b\"]}");
        assertEquals(Optional.empty(), inf.inferItemsPath(root));
        assertEquals(JsonStructureInference.PaginationKind.UNKNOWN, inf.inferPagination(root, Map.of()));
    }

    @Test void a_total_count_header_is_a_paging_signal() throws Exception {
        assertEquals(JsonStructureInference.PaginationKind.UNKNOWN,
            inf.inferPagination(M.readTree("[{\"id\":1}]"), Map.of("X-Total-Count", List.of("120"))));
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
            Object val = JsonPath.read(json, (String) p.get("path"));
            assertNotNull(val, "path " + p.get("path") + " did not resolve");
        }
    }

    /**
     * The path must keep extracting even when the field name contains the quoting/escape characters
     * themselves — a single ", a ', both, or a backslash. Raw embedding produces an invalid JsonPath
     * (or points at the wrong key); the path builder must escape and use one consistent quote style.
     */
    @Test void promote_paths_survive_quotes_and_backslashes() throws Exception {
        ObjectNode item = M.createObjectNode();
        item.put("it's", "v_apostrophe");
        item.put("a\"b", "v_quote");
        item.put("a\"'b", "v_both");
        item.put("a\\b", "v_backslash");
        item.put("ok", "v_clean");

        var promotes = inf.inferPromotes(item);
        String json = M.writeValueAsString(item);

        List<Object> read = promotes.stream()
            .map(p -> JsonPath.<Object>read(json, (String) p.get("path")))
            .toList();
        assertEquals(
            Set.of("v_apostrophe", "v_quote", "v_both", "v_backslash", "v_clean"),
            Set.copyOf(read),
            "some paths were invalid or pointed at the wrong key: "
                + promotes.stream().map(p -> p.get("path")).toList());
    }

    /**
     * #1785: a field named like a column the hybrid table already has is drafted under another name — the draft
     * would be refused by {@code lm validate}, and before that died at the first run. The path keeps the key.
     */
    @Test void promotes_never_take_a_name_the_hybrid_table_already_has() throws Exception {
        ObjectNode item = M.createObjectNode();
        for (String reserved : HybridJsonbSpec.RESERVED_COLUMNS) item.put(reserved, "v_" + reserved);

        var promotes = inf.inferPromotes(item);
        String json = M.writeValueAsString(item);

        assertEquals(HybridJsonbSpec.RESERVED_COLUMNS.size(), promotes.size());
        for (var p : promotes) {
            assertFalse(HybridJsonbSpec.RESERVED_COLUMNS.contains((String) p.get("name")),
                "drafted a reserved name: " + p.get("name"));
        }
        // every field is still read, by its own key
        assertEquals(
            HybridJsonbSpec.RESERVED_COLUMNS.stream().map(r -> "v_" + r).collect(Collectors.toSet()),
            promotes.stream().map(p -> JsonPath.<String>read(json, (String) p.get("path"))).collect(Collectors.toSet()));
    }
}
