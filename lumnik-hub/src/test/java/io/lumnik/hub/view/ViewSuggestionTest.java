package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ViewSuggestionTest {

    @Test
    void parses_a_plain_json_object() {
        ViewSuggestion s = ViewSuggestion.parse(
            "{\"table\":\"connector.v_customer\",\"title\":\"Clients gold\","
            + "\"columns\":[\"customer_code\",\"name\"],\"filters\":[\"segment=GOLD\"]}");
        assertEquals("connector.v_customer", s.table());
        assertEquals("Clients gold", s.title());
        assertEquals(List.of("customer_code", "name"), s.columns());
        assertEquals(List.of("segment=GOLD"), s.filters());
        assertFalse(s.abstain());
    }

    @Test
    void parses_json_wrapped_in_markdown_fences() {
        // Small local models wrap their JSON in ``` fences — extract the outermost {…}.
        ViewSuggestion s = ViewSuggestion.parse(
            "```json\n{\"title\":\"Desk\",\"columns\":[\"name\"]}\n```");
        assertEquals("Desk", s.title());
        assertEquals(List.of("name"), s.columns());
    }

    @Test
    void parses_the_abstain_form() {
        ViewSuggestion s = ViewSuggestion.parse(
            "{\"abstain\": true, \"reason\": \"no order data in this scope\"}");
        assertTrue(s.abstain());
        assertEquals("no order data in this scope", s.reason());
    }

    @Test
    void tolerates_unknown_fields() {
        ViewSuggestion s = ViewSuggestion.parse(
            "{\"title\":\"Desk\",\"columns\":[\"name\"],\"confidence\":0.9}");
        assertEquals("Desk", s.title());
    }

    @Test
    void refuses_output_without_a_json_object() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ViewSuggestion.parse("Sure! Here is the view you asked for."));
        assertTrue(e.getMessage().contains("not a JSON object"));
    }

    @Test
    void refuses_malformed_json() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ViewSuggestion.parse("{\"columns\": [unquoted]}"));
        assertTrue(e.getMessage().contains("not valid JSON"));
    }

    @Test
    void hugeNonJsonOutput_isTruncatedInTheMessage() {
        // The raw output is carried separately on failures/retries — the message stays small.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ViewSuggestion.parse("x".repeat(10_000)));
        assertTrue(e.getMessage().length() < 300, "message was " + e.getMessage().length() + " chars");
        assertTrue(e.getMessage().contains("…"));
    }
}
