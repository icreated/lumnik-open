package io.lumnik.hub.connector.rest.pagination.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PaginationPresetRegistryTest {

    @Inject PaginationPresetRegistry registry;
    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode node(String json) throws Exception { return M.readTree(json); }

    @Test
    void expands_a_preset_to_the_real_kind() throws Exception {
        JsonNode out = registry.expand(node("{\"kind\":\"spring-page\"}"));
        assertEquals("page", out.path("kind").asText());
        assertEquals("$.content", out.path("response_path").asText());
        assertEquals("$.totalPages", out.path("total_pages_path").asText());
    }

    @Test
    void user_override_wins() throws Exception {
        JsonNode out = registry.expand(node("{\"kind\":\"spring-page\",\"size\":\"50\"}"));
        assertEquals("50", out.path("size").asText());
        assertEquals("page", out.path("kind").asText());
    }

    @Test
    void substitutes_placeholder_from_user_field() throws Exception {
        JsonNode out = registry.expand(node("{\"kind\":\"spring-hateoas\",\"items\":\"products\"}"));
        assertEquals("body-link", out.path("kind").asText());
        assertEquals("$._embedded.products", out.path("response_path").asText());
        assertTrue(out.path("items").isMissingNode(), "the placeholder var must not leak as a strategy field");
    }

    @Test
    void unknown_preset_is_returned_unchanged() throws Exception {
        JsonNode in = node("{\"kind\":\"page\",\"page_param\":\"p\"}");
        assertEquals(in, registry.expand(in));
    }

    @Test
    void required_overrides_are_reported() {
        assertTrue(registry.requiredOverrides("spring-hateoas").contains("items"));
        assertTrue(registry.requiredOverrides("spring-page").isEmpty());
        assertFalse(registry.isPreset("page"));
        assertTrue(registry.isPreset("spring-page"));
    }
}
