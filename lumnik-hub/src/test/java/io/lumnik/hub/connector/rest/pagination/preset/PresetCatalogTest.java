package io.lumnik.hub.connector.rest.pagination.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            new P("spring-page", null), new P("spring-paged-model", null), new P("spring-slice", null),
            new P("micronaut", null),
            new P("drf-page", null), new P("drf-limit-offset", null), new P("fastapi", null),
            new P("nestjs", null), new P("json-api", null), new P("sequelize", null),
            new P("stripe", null), new P("spring-hateoas", "\"items\":\"products\""),
            new P("prestashop", "\"resource\":\"products\""));
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
        // #1771: every DRF page carries its count — the witness that a run read the whole list
        assertEquals("$.count", out.path("total_path").asText());
    }

    /** #1794: nestjs-paginate puts the list's total on every page, as DRF does its count. */
    @Test
    void nestjs_reads_the_total_every_page_carries() throws Exception {
        JsonNode out = expand("nestjs", null);
        assertEquals("body-link", out.path("kind").asText());
        assertEquals("$.links.next", out.path("link_path").asText());
        assertEquals("$.data", out.path("response_path").asText());
        assertEquals("$.meta.totalItems", out.path("total_path").asText());
    }

    /** #1796: every one of these frameworks puts the list's total on each page — the witness a run is measured against. */
    @ParameterizedTest
    @CsvSource({
        "spring-page,        $.totalElements",
        "spring-paged-model, $.page.totalElements",
        "micronaut,          $.totalSize",
        "fastapi,            $.total",
        "spring-hateoas,     $.page.totalElements"})
    void a_preset_whose_pages_carry_a_total_declares_it(String preset, String totalPath) throws Exception {
        String extra = "spring-hateoas".equals(preset) ? "\"items\":\"products\"" : null;
        assertEquals(totalPath, expand(preset, extra).path("total_path").asText());
    }

    /** #1800: Micronaut 4 sends no totalPages; Micronaut 3 does — the preset stops on whichever the page carries. */
    @Test
    void micronaut_reads_the_total_and_the_page_count_of_the_version_that_sends_it() throws Exception {
        var out = expand("micronaut", null);
        assertEquals("$.totalSize", out.path("total_path").asText());
        assertEquals("$.totalPages", out.path("total_pages_path").asText());
    }

    /** #1642: Spring Data's PagedModel (VIA_DTO) puts its counters under "page". */
    @Test
    void spring_paged_model_reads_its_total_under_page() throws Exception {
        var out = expand("spring-paged-model", null);
        assertEquals("page", out.path("kind").asText());
        assertEquals("0", out.path("start_page").asText());
        assertEquals("$.page.totalPages", out.path("total_pages_path").asText());
        assertEquals("$.content", out.path("response_path").asText());
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

    /** #1754: PrestaShop's Webservice — limit=offset,count in one parameter, the list named after the resource. */
    @Test
    void prestashop_writes_offset_and_count_in_limit_and_reads_the_resource() throws Exception {
        var out = expand("prestashop", "\"resource\":\"categories\",\"limit\":50");
        assertEquals("offset", out.path("kind").asText());
        assertEquals("limit", out.path("offset_param").asText());
        assertEquals("limit", out.path("limit_param").asText());
        assertEquals("$.categories", out.path("response_path").asText());
        assertEquals(50, out.path("limit").asInt());
        assertTrue(registry.requiredOverrides("prestashop").contains("resource"));
    }
}
