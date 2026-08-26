package io.lumnik.hub.connector.rest.soap;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class XmlBodyParserTest {

    private static final String TWO = """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <Out><ListOfCustomer>
              <Customer><Id>a</Id></Customer>
              <Customer><Id>b</Id></Customer>
            </ListOfCustomer></Out>
          </soap:Body>
        </soap:Envelope>""";

    private static final String ONE = """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <Out><ListOfCustomer>
              <Customer><Id>a</Id></Customer>
            </ListOfCustomer></Out>
          </soap:Body>
        </soap:Envelope>""";

    private RestResponse raw(String body) {
        return new RestResponse(200, Map.of(), body, null);
    }

    @Test
    void strips_namespace_prefixes_so_jsonpath_is_clean() {
        JsonNode tree = new XmlBodyParser("$.Body.Out.ListOfCustomer.Customer").reparse(raw(TWO)).json();
        // soap:Body -> Body (the prefix is gone)
        assertThat(tree.has("Body")).isTrue();
        assertThat(tree.get("Body").has("Out")).isTrue();
    }

    @Test
    void many_elements_stay_a_list() {
        JsonNode tree = new XmlBodyParser("$.Body.Out.ListOfCustomer.Customer").reparse(raw(TWO)).json();
        assertThat(tree.at("/Body/Out/ListOfCustomer/Customer").isArray()).isTrue();
        assertThat(tree.at("/Body/Out/ListOfCustomer/Customer")).hasSize(2);
    }

    @Test
    void single_element_is_forced_to_a_list() {
        JsonNode tree = new XmlBodyParser("$.Body.Out.ListOfCustomer.Customer").reparse(raw(ONE)).json();
        // Without the force-list, one <Customer> deserializes to an object and the row is silently dropped.
        assertThat(tree.at("/Body/Out/ListOfCustomer/Customer").isArray()).isTrue();
        assertThat(tree.at("/Body/Out/ListOfCustomer/Customer")).hasSize(1);
    }

    private static final String REPEATED_WRAPPER = """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <Out><Customer><Id>a</Id></Customer></Out>
            <Out><Customer><Id>b</Id></Customer></Out>
          </soap:Body>
        </soap:Envelope>""";

    @Test
    void intermediate_repeated_wrapper_is_not_coerced_v1_limitation() {
        // PINS the v1 boundary: forceList only coerces the TERMINAL segment. Here the intermediate
        // wrapper <Out> repeats, so $.Body.Out is an array mid-walk; the walk returns early and the
        // terminal <Customer> is left untouched. A future change to walk arrays must be deliberate.
        JsonNode tree = new XmlBodyParser("$.Body.Out.Customer").reparse(raw(REPEATED_WRAPPER)).json();
        // The intermediate segment is the array (the repeated <Out>) — not the terminal <Customer>.
        assertThat(tree.at("/Body/Out").isArray()).isTrue();
        // Each <Out>'s <Customer> stays an object: the terminal force-list did NOT happen.
        assertThat(tree.at("/Body/Out/0/Customer").isObject()).isTrue();
        assertThat(tree.at("/Body/Out/0/Customer").isArray()).isFalse();
    }

    @Test
    void malformed_xml_raises_a_connector_exception() {
        assertThatThrownBy(() -> new XmlBodyParser("$.Body").reparse(raw("<not-closed>")))
            .isInstanceOf(ConnectorException.class);
    }

    @Test
    void blank_body_yields_an_empty_object_tree() {
        JsonNode tree = new XmlBodyParser("$.Body").reparse(raw("")).json();
        assertThat(tree.isObject()).isTrue();
        assertThat(tree).isEmpty();
    }

    @Test
    void non_2xx_response_raises_transient_failure_exception() {
        // A 503 with an HTML body (infra error) must surface as transient, not a permanent parse failure.
        RestResponse resp = new RestResponse(503, Map.of(), "<html>Service Unavailable</html>", null);
        assertThatThrownBy(() -> new XmlBodyParser("$.Body").reparse(resp))
            .isInstanceOf(TransientFailureException.class);
    }

    @Test
    void xml_with_dtd_or_external_entity_is_rejected() {
        // XXE guard: a SOAP response embedding a DTD/external entity must never trigger
        // entity resolution (file read, SSRF). The parser must reject the document.
        String xxe = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><Out><Data>&xxe;</Data></Out></soap:Body></soap:Envelope>";
        RestResponse resp = new RestResponse(200, Map.of(), xxe, null);
        assertThatThrownBy(() -> new XmlBodyParser("$.Body.Out.Data").reparse(resp))
            .isInstanceOf(ConnectorException.class);
    }

    @Test
    void client_error_4xx_is_a_permanent_connector_exception() {
        // 401/403/404 are misconfigurations — RetryExecutor does not retry them, so we must not
        // mark them retryable either (would cause endless chunk-level retries vs a fast DLQ).
        RestResponse resp = new RestResponse(404, Map.of(), "<html>Not Found</html>", null);
        assertThatThrownBy(() -> new XmlBodyParser("$.Body").reparse(resp))
            .isInstanceOf(ConnectorException.class)
            .isNotInstanceOf(TransientFailureException.class);
    }
}
