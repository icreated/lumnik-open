package io.lumnik.hub.connector.rest.pagination.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PresetCatalogTest {

    @Inject PaginationPresetRegistry registry;
    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> REAL_KINDS =
        Set.of("page", "offset", "cursor", "link-header", "relay", "custom", "body-link");

    private JsonNode expand(String name, String extraJson) throws Exception {
        String json = extraJson == null ? "{\"kind\":\"" + name + "\"}"
            : "{\"kind\":\"" + name + "\"," + extraJson + "}";
        return registry.expand(M.readTree(json));
    }

    @Test
    void every_catalogue_preset_expands_to_a_real_kind() throws Exception {
        record P(String name, String extra) {}
        var presets = List.of(
            new P("spring-page", null), new P("spring-slice", null), new P("micronaut", null),
            new P("drf-page", null), new P("drf-limit-offset", null), new P("fastapi", null),
            new P("nestjs", null), new P("json-api", null), new P("sequelize", null),
            new P("stripe", null), new P("spring-hateoas", "\"items\":\"products\""));
        for (P p : presets) {
            JsonNode out = expand(p.name(), p.extra());
            assertTrue(REAL_KINDS.contains(out.path("kind").asText()),
                () -> p.name() + " expanded to non-real kind: " + out);
            assertFalse(out.path("response_path").asText().isBlank(),
                () -> p.name() + " has no response_path: " + out);
        }
    }

    @Test
    void drf_page_uses_body_link() throws Exception {
        JsonNode out = expand("drf-page", null);
        assertEquals("body-link", out.path("kind").asText());
        assertEquals("$.next", out.path("link_path").asText());
        assertEquals("$.results", out.path("response_path").asText());
    }

    @Test
    void spring_slice_carries_last_path() throws Exception {
        assertEquals("$.last", expand("spring-slice", null).path("last_path").asText());
    }

    @Test
    void fastapi_is_one_indexed() throws Exception {
        var out = expand("fastapi", null);
        assertEquals("1", out.path("start_page").asText());
        assertEquals("$.items", out.path("response_path").asText());
    }
}
