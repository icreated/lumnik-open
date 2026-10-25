package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.soap.XmlBodyParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void redacts_bearer() { assertEquals("Bearer <redacted>", SecretRedactor.redactHeader("Authorization", "Bearer sk_live_xyz")); }
    @Test void redacts_api_key() { assertEquals("<redacted>", SecretRedactor.redactHeader("X-API-Key", "secret")); }
    @Test void passthrough()    { assertEquals("application/json", SecretRedactor.redactHeader("Content-Type", "application/json")); }

    // ── a source that echoes the credential it received (#1393 security review, #1394 reviews) ──

    /** An error body: not ingested, so the bare value goes too. */
    private static String echoed(RestRequest signed, String body) {
        return SecretRedactor.withoutEchoedCredentials(RestResponse.of(401, Map.of(), body), signed, false).body();
    }

    @Test void an_echoed_bearer_is_replaced_as_sent_json_escaped_and_url_encoded() {
        RestRequest signed = RestRequest.get("https://saas.example/orders").withHeader("Authorization", "Bearer ab/cd+ef==gh");
        assertThat(echoed(signed, "{\"seen\":\"Bearer ab/cd+ef==gh\"}")).isEqualTo("{\"seen\":\"Bearer <redacted>\"}");
        assertThat(echoed(signed, "{\"seen\":\"Bearer ab\\/cd+ef==gh\"}")).isEqualTo("{\"seen\":\"Bearer <redacted>\"}");
        assertThat(echoed(signed, "token=ab%2Fcd%2Bef%3D%3Dgh")).isEqualTo("token=<redacted>");
    }

    @Test void a_basic_pair_is_replaced_encoded_and_decoded() {
        String pair = "lumnik:the-password";
        String b64 = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        RestRequest signed = RestRequest.get("https://erp.example/x").withHeader("Authorization", "Basic " + b64);
        assertThat(echoed(signed, "got " + b64 + " for " + pair)).isEqualTo("got <redacted> for <redacted>")
            .doesNotContain("the-password");
    }

    @Test void an_api_key_in_a_header_or_the_query_is_replaced() {
        RestRequest signed = RestRequest.get("https://erp.example/x").withHeader("X-API-Key", "header-key-value")
            .withQuery("apikey", "query-key-value").withQuery("page", "1234567890");
        assertThat(echoed(signed, "header-key-value query-key-value page 1234567890"))
            .isEqualTo("<redacted> <redacted> page 1234567890");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "12345678"})
    void a_body_to_ingest_loses_the_structured_forms_only_whatever_the_credential_s_length(String token) {
        // Reviews of #1394: no length tells a bare credential from data — {"customer_id":12345678} stays as written.
        RestRequest signed = RestRequest.get("https://erp.example/x").withHeader("Authorization", "Bearer " + token)
            .withQuery("apikey", token);
        RestResponse data = RestResponse.of(200, Map.of(), "{\"customer_id\":" + token + ",\"code\":\"" + token + "\"}");
        assertThat(SecretRedactor.withoutEchoedCredentials(data, signed, true)).isSameAs(data);
        RestResponse echo = RestResponse.of(200, Map.of(),
            "{\"seen\":\"Bearer " + token + "\",\"url\":\"/x?apikey=" + token + "\",\"id\":" + token + "}");
        assertThat(SecretRedactor.withoutEchoedCredentials(echo, signed, true).body()).isEqualTo(
            "{\"seen\":\"Bearer <redacted>\",\"url\":\"/x?apikey=<redacted>\",\"id\":" + token + "}");
    }

    @Test void an_error_body_loses_the_bare_value_too_a_short_one_where_it_stands_alone() {
        RestRequest signed = RestRequest.get("https://erp.example/x").withHeader("X-API-Key", "k9z");
        assertThat(echoed(signed, "{\"error\":\"unknown key k9z\",\"hint\":\"k9zz\"}"))
            .isEqualTo("{\"error\":\"unknown key <redacted>\",\"hint\":\"k9zz\"}");
    }

    @Test void an_untouched_body_keeps_its_response() {
        RestRequest signed = RestRequest.get("https://erp.example/x").withHeader("X-API-Key", "abc");
        RestResponse resp = RestResponse.of(200, Map.of(), "{\"name\":\"abcdef\"}");
        assertThat(SecretRedactor.withoutEchoedCredentials(resp, signed, true)).isSameAs(resp);
    }

    @Test void the_marker_in_an_xml_body_is_text_so_the_body_still_parses() {
        // #1395: "<redacted>" is a tag — a SOAP page that echoed the bearer no longer parsed, and a Fault was no
        // longer read as one. In markup the marker is escaped, and parses back to the same text.
        RestRequest signed = RestRequest.get("https://erp.example/eai").withHeader("Authorization", "Bearer ab/cd+ef==gh");
        String page = "<Envelope><Body><Out><Seen>Bearer ab/cd+ef==gh</Seen></Out></Body></Envelope>";
        String fault = "<Envelope><Body><Fault><faultstring>denied ab/cd+ef==gh</faultstring></Fault></Body></Envelope>";

        String ingested = SecretRedactor.withoutEchoedCredentials(RestResponse.of(200, Map.of(), page), signed, true).body();
        assertThat(XmlBodyParser.strippedTree(ingested).path("Body").path("Out").path("Seen").asText())
            .isEqualTo("Bearer <redacted>");
        assertThat(XmlBodyParser.strippedTree(echoed(signed, fault)).path("Body").path("Fault").path("faultstring").asText())
            .isEqualTo("denied <redacted>");
        assertThat(echoed(signed, "\uFEFF\n" + fault)).contains("denied &lt;redacted&gt;");   // a BOM, a blank line first
    }
}
