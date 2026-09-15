package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TableDefaultsMergerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void merge_tableWins_overDefaults() throws Exception {
        JsonNode defaults = mapper.readTree("{\"chunk_size\":500,\"schema\":\"public\"}");
        JsonNode table = mapper.readTree("{\"name\":\"invoices\",\"chunk_size\":1000}");
        JsonNode merged = TableDefaultsMerger.merge(defaults, table);
        assertEquals("invoices", merged.path("name").asText());
        assertEquals(1000, merged.path("chunk_size").asInt());
        assertEquals("public", merged.path("schema").asText());
    }

    @Test void merge_nullDefaults_returnsTableUnchanged() throws Exception {
        JsonNode table = mapper.readTree("{\"name\":\"x\",\"mode\":\"full\"}");
        JsonNode merged = TableDefaultsMerger.merge(null, table);
        assertEquals("x", merged.path("name").asText());
        assertEquals("full", merged.path("mode").asText());
    }

    /**
     * The rule at its own tier (#551): a table field written with nothing after it must not win over
     * the default. It is a NullNode — absent by ConfigDefRunner's reading, which is what let the
     * merged table validate clean — and copying it erased the value the same manifest had just
     * declared. Pinned here as well as at the apply door because this node is what BOTH read.
     */
    @Test void merge_tableFieldWrittenEmpty_doesNotOverrideTheDefault() throws Exception {
        JsonNode defaults = mapper.readTree("{\"schema\":\"legacy\"}");
        JsonNode table = mapper.readTree("{\"name\":\"invoices\",\"schema\":null}");
        JsonNode merged = TableDefaultsMerger.merge(defaults, table);
        assertEquals("legacy", merged.path("schema").asText());
    }

    /** And the same on the other side of the merge: a default written empty declares nothing. */
    @Test void merge_defaultWrittenEmpty_isNotADeclaredValue() throws Exception {
        JsonNode defaults = mapper.readTree("{\"schema\":null}");
        JsonNode table = mapper.readTree("{\"name\":\"invoices\"}");
        JsonNode merged = TableDefaultsMerger.merge(defaults, table);
        assertTrue(merged.path("schema").isMissingNode());
    }

    @Test void merge_missingDefaultsNode_returnsTableUnchanged() throws Exception {
        JsonNode defaults = mapper.readTree("{}");
        JsonNode table = mapper.readTree("{\"name\":\"x\"}");
        JsonNode merged = TableDefaultsMerger.merge(defaults, table);
        assertEquals("x", merged.path("name").asText());
        assertTrue(merged.path("chunk_size").isMissingNode());
    }
}
