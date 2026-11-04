package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #1755: a REST answer in XML, read when the endpoint declares {@code response_format: xml} — PrestaShop's Webservice by default. */
class XmlRestBodyParserTest {

    private static final Map<String, List<String>> XML = Map.of("Content-Type", List.of("text/xml;charset=utf-8"));

    /** As PrestaShop 8.2.8 answers {@code /api/products?display=[id,reference,price,name]}. */
    private static final String PRODUCTS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <prestashop xmlns:xlink="http://www.w3.org/1999/xlink">
        <products>
        <product>
        \t<id><![CDATA[1]]></id>
        \t<reference><![CDATA[demo_1]]></reference>
        \t<price><![CDATA[23.900000]]></price>
        \t<name><language id="1" xlink:href="http://shop.test/api/languages/1"><![CDATA[Hummingbird printed t-shirt]]></language></name>
        </product>
        <product>
        \t<id><![CDATA[2]]></id>
        \t<reference><![CDATA[demo_3]]></reference>
        \t<price><![CDATA[35.900000]]></price>
        \t<name><language id="1" xlink:href="http://shop.test/api/languages/1"><![CDATA[Hummingbird printed sweater]]></language></name>
        </product>
        </products>
        </prestashop>
        """;

    private static JsonNode tree(String body, String responsePath) {
        return new XmlRestBodyParser(responsePath).reparse(RestResponse.of(200, XML, body)).json();
    }

    @Test
    void a_prestashop_list_reads_as_rows_with_attributes_under_at_and_text_under_hash() {
        JsonNode tree = tree(PRODUCTS, "$.products.product");
        List<Map<String, Object>> rows = PaginationSupport.items(tree, "$.products.product");
        assertThat(rows).hasSize(2);
        assertThat(tree.at("/products/product/0/id").asText()).isEqualTo("1");
        assertThat(tree.at("/products/product/0/price").asText()).isEqualTo("23.900000");
        JsonNode language = tree.at("/products/product/1/name/language");
        assertThat(language.get("@id").asText()).isEqualTo("1");
        assertThat(language.get("@href").asText()).isEqualTo("http://shop.test/api/languages/1");
        assertThat(language.get("#text").asText()).isEqualTo("Hummingbird printed sweater");
    }

    @Test
    void one_row_alone_is_still_a_list_at_the_response_path() {
        String one = "<prestashop><products><product><id>7</id></product></products></prestashop>";
        assertThat(PaginationSupport.items(tree(one, "$.products.product"), "$.products.product"))
            .containsExactly(Map.of("id", "7"));
    }

    @Test
    void an_element_of_text_alone_at_the_response_path_is_no_row() {
        // Review of #1758: a row is an object; a scalar is never wrapped into a list the run would read as rows.
        JsonNode tree = tree("<r><ids><id>5</id></ids></r>", "$.ids.id");
        assertThat(tree.at("/ids/id").isTextual()).isTrue();
        assertThat(PaginationSupport.items(tree, "$.ids.id")).isEmpty();
    }

    @Test
    void an_element_carrying_only_attributes_is_an_object_of_attributes() {
        // The ECB's daily rates: every value is an attribute.
        String ecb = "<Envelope><Cube><Cube time=\"2026-10-30\"><Cube currency=\"USD\" rate=\"1.0842\"/>"
            + "<Cube currency=\"JPY\" rate=\"163.21\"/></Cube></Cube></Envelope>";
        JsonNode tree = tree(ecb, "$.Cube.Cube.Cube");
        assertThat(PaginationSupport.items(tree, "$.Cube.Cube.Cube"))
            .containsExactly(Map.of("@currency", "USD", "@rate", "1.0842"), Map.of("@currency", "JPY", "@rate", "163.21"));
        assertThat(tree.at("/Cube/Cube/@time").asText()).isEqualTo("2026-10-30");
    }

    @Test
    void an_empty_element_is_an_empty_value() {
        assertThat(tree("<r><list><item><note/></item></list></r>", "$.list.item").at("/list/item/0/note").asText()).isEmpty();
    }

    @Test
    void an_empty_answer_is_an_empty_page_not_a_failure() {
        // Review of #1758: as JSON and SOAP read it — the read ends, nothing is refused.
        assertThat(PaginationSupport.items(tree("", "$.products.product"), "$.products.product")).isEmpty();
    }

    @Test
    void a_doctype_is_refused_so_no_entity_is_ever_expanded() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r><a>&x;</a></r>";
        assertThatThrownBy(() -> tree(xxe, "$.a"))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("the response is not readable XML (Content-Type text/xml;charset=utf-8)");
    }

    @Test
    void a_body_that_is_not_xml_is_refused_by_its_content_type_never_quoted() {
        RestResponse json = RestResponse.of(200, Map.of("Content-Type", List.of("application/json")), "{\"secret\":\"s3cr3t\"}");
        assertThatThrownBy(() -> new XmlRestBodyParser("$.a").reparse(json))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("the response is not readable XML (Content-Type application/json)");
    }

    @Test
    void an_error_status_is_refused_before_the_body_is_read() {
        assertThatThrownBy(() -> new XmlRestBodyParser("$.a").reparse(RestResponse.of(401, XML, "<errors/>")))
            .hasMessageContaining("401");
    }

    @Test
    void the_json_refusal_of_an_xml_answer_points_at_response_format_xml() {
        assertThatThrownBy(() -> JsonBodyParser.INSTANCE.reparse(RestResponse.of(200, XML, PRODUCTS)))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("the response is not JSON (Content-Type text/xml;charset=utf-8) — rest-generic reads JSON;"
                + " an endpoint answering XML declares response_format: xml");
    }
}
