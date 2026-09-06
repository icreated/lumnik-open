package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ValidationError;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SoapConnectorValidatorTest {

    @Inject RestConnectorValidator validator;
    private static final ObjectMapper M = new ObjectMapper();

    private List<ValidationError> validate(String specJson) throws Exception {
        JsonNode spec = M.readTree(specJson);
        return validator.validateCrossField(spec);
    }

    private static final String VALID = """
        {"base_url":"http://h","endpoints":[{
          "id":"customers","kind":"soap","path":"/svc","soap_action":"A",
          "body":"<In><PageSize>{{page_size}}</PageSize></In>",
          "pagination":{"kind":"soap","page_size":100,
            "last_page_path":"$.Body.Out.LastPage",
            "response_path":"$.Body.Out.ListOfCustomer.Customer"},
          "target":{"table":"ext.cust","unique_key":"$.Id"}
        }]}""";

    @Test
    void a_well_formed_soap_endpoint_has_no_errors() throws Exception {
        assertThat(validate(VALID)).isEmpty();
    }

    @Test
    void soap_endpoint_without_body_is_rejected() throws Exception {
        String bad = VALID.replace("\"body\":\"<In><PageSize>{{page_size}}</PageSize></In>\",", "");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("body"));
    }

    @Test
    void soap_pagination_without_response_path_is_rejected() throws Exception {
        String bad = VALID.replace("\"response_path\":\"$.Body.Out.ListOfCustomer.Customer\"", "\"x\":\"y\"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("response_path"));
    }

    @Test
    void soap_pagination_without_page_size_is_rejected() throws Exception {
        // Without an explicit page_size the strategy silently defaults to 100, which can mismatch
        // the service's actual page size and cause silent skips or duplicates.
        String bad = VALID.replace("\"page_size\":100,", "");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("page_size"));
    }

    @Test
    void soap_response_path_with_whitespace_is_rejected() throws Exception {
        // A trailing space survives isPlainDottedPath but makes forceList look up a
        // space-suffixed key — silently no-ops, reintroducing single-element data loss.
        String bad = VALID.replace("\"$.Body.Out.ListOfCustomer.Customer\"",
            "\"$.Body.Out.ListOfCustomer.Customer \"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("response_path"));
    }

    @Test
    void bare_dollar_response_path_is_rejected() throws Exception {
        // "$" has no "." separator — forceList would look for key "$" in the root and silently no-op.
        String bad = VALID.replace("\"$.Body.Out.ListOfCustomer.Customer\"", "\"$\"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("response_path"));
    }

    @Test
    void recursive_descent_response_path_is_rejected() throws Exception {
        // "$..Customer" → after "$." strip p = ".Customer" — leading dot = empty first segment;
        // forceList split yields ["", "Customer"], misses the root walk, force-list is a no-op.
        String bad = VALID.replace("\"$.Body.Out.ListOfCustomer.Customer\"", "\"$..Customer\"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("response_path"));
    }

    @Test
    void soap_response_path_with_jsonpath_operators_is_rejected() throws Exception {
        // JsonPath operators ([*], ..) bypass forceList — a single <Customer> stays an object
        // and the row is silently dropped. The validator must catch this before runtime.
        String bad = VALID.replace(
            "\"response_path\":\"$.Body.Out.ListOfCustomer.Customer\"",
            "\"response_path\":\"$.Body.Out.ListOfCustomer[*].Customer\"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("response_path"));
    }

    @Test
    void soap_endpoint_with_non_soap_pagination_kind_is_rejected() throws Exception {
        // kind:soap + pagination.kind:offset is a misconfiguration: wrong request builder + wrong parser.
        String bad = VALID.replace("\"pagination\":{\"kind\":\"soap\",\"page_size\"",
            "\"pagination\":{\"kind\":\"offset\",\"page_size\"");
        assertThat(validate(bad))
            .anySatisfy(e -> assertThat(e.path()).contains("pagination.kind"));
    }
}
