package io.lumnik.hub.connector.rest.soap;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SoapFaultValidatorTest {

    private RestResponse resp(int status, String body) {
        return new RestResponse(status, Map.of(), body, null);
    }

    private static final String FAULT = """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body><soap:Fault>
            <faultcode>Server</faultcode><faultstring>boom</faultstring>
          </soap:Fault></soap:Body>
        </soap:Envelope>""";

    private static final String OK = """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body><Out><Id>a</Id></Out></soap:Body>
        </soap:Envelope>""";

    @Test
    void a_fault_throws_a_permanent_connector_exception_even_on_http_500() {
        assertThatThrownBy(() -> new SoapFaultValidator().validate(resp(500, FAULT)))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("boom");
    }

    @Test
    void a_clean_envelope_passes() {
        assertThatCode(() -> new SoapFaultValidator().validate(resp(200, OK)))
            .doesNotThrowAnyException();
    }

    @Test
    void fault_without_faultstring_uses_bounded_message() {
        // When faultstring is absent the fallback must not dump the entire fault node into logs.
        String detail = "x".repeat(500);
        String body = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><soap:Fault><faultcode>Server</faultcode>"
            + "<detail>" + detail + "</detail>"
            + "</soap:Fault></soap:Body></soap:Envelope>";
        assertThatThrownBy(() -> new SoapFaultValidator().validate(resp(500, body)))
            .isInstanceOf(ConnectorException.class)
            .satisfies(t -> assertThat(t.getMessage()).hasSizeLessThan(300));
    }

    @Test
    void long_faultstring_is_bounded_to_prevent_log_bloat() {
        // faultstring can be arbitrarily large — it must get the same snippet() bound as the fallback.
        String longFault = "x".repeat(500);
        String body = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><soap:Fault><faultcode>Server</faultcode>"
            + "<faultstring>" + longFault + "</faultstring>"
            + "</soap:Fault></soap:Body></soap:Envelope>";
        assertThatThrownBy(() -> new SoapFaultValidator().validate(resp(500, body)))
            .isInstanceOf(ConnectorException.class)
            .satisfies(t -> assertThat(t.getMessage()).hasSizeLessThan(300));
    }

    @Test
    void a_non_xml_5xx_body_is_left_for_status_based_retry() {
        // An HTML 503 page is not a SOAP fault — do not turn a retryable transport error permanent.
        assertThatCode(() -> new SoapFaultValidator().validate(resp(503, "<html>down</html>")))
            .doesNotThrowAnyException();
    }
}
