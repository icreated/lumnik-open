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

    @Test void merge_missingDefaultsNode_returnsTableUnchanged() throws Exception {
        JsonNode defaults = mapper.readTree("{}");
        JsonNode table = mapper.readTree("{\"name\":\"x\"}");
        JsonNode merged = TableDefaultsMerger.merge(defaults, table);
        assertEquals("x", merged.path("name").asText());
        assertTrue(merged.path("chunk_size").isMissingNode());
    }
}
