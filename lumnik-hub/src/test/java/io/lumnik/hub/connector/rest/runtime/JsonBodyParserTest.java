package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #1644: a body that is not JSON must never read as an empty page. */
class JsonBodyParserTest {

    private static RestResponse ok(String contentType, String body) {
        return RestResponse.of(200, contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType)), body);
    }

    @Test
    void an_xml_body_is_refused_naming_its_content_type_never_its_content() {
        RestResponse xml = ok("application/xml", "<orders><order><id>SECRET-1</id></order></orders>");
        ConnectorException e = assertThrows(ConnectorException.class, () -> JsonBodyParser.INSTANCE.reparse(xml));
        assertEquals("the response is not JSON (Content-Type application/xml) — rest-generic reads JSON", e.getMessage());
    }

    /** An older application whose session expired answers its login page with a 200. */
    @Test
    void an_html_login_page_served_with_200_is_refused() {
        RestResponse login = ok("text/html;charset=UTF-8", "<html><form action=\"j_security_check\">…</form></html>");
        ConnectorException e = assertThrows(ConnectorException.class, () -> JsonBodyParser.INSTANCE.reparse(login));
        assertTrue(e.getMessage().contains("Content-Type text/html;charset=UTF-8"), e::getMessage);
        assertFalse(e.getMessage().contains("j_security_check"), e::getMessage);
    }

    @Test
    void a_broken_json_body_without_content_type_is_refused() {
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> JsonBodyParser.INSTANCE.reparse(ok(null, "{\"items\": [")));
        assertTrue(e.getMessage().contains("no Content-Type"), e::getMessage);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{\"items\":[]}", "[]", "{}", " { } ", "{\n}", "", "  "})
    void json_an_empty_object_and_an_empty_body_pass_as_they_are(String body) {
        RestResponse r = ok("application/json", body);
        assertSame(r, JsonBodyParser.INSTANCE.reparse(r));
    }
}
