package io.lumnik.hub.connector.rest.soap;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;

class SoapRequestBuilderTest {

    private static final UnaryOperator<String> SECRETS =
        name -> Map.of("SIEBEL_PWD", "s3cr3t").get(name);

    private static final String BODY =
        "<In><PageSize>{{page_size}}</PageSize><StartRowNum>{{cursor}}</StartRowNum></In>";

    @Test
    void wraps_body_in_an_envelope_and_substitutes_pagination_placeholders() {
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", null, BODY, "100", "0", SECRETS, SECRETS);

        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.url()).isEqualTo("http://h/svc");
        assertThat(r.body()).contains("<soap:Envelope");
        assertThat(r.body()).contains("<soap:Body>");
        assertThat(r.body()).contains("<PageSize>100</PageSize>");
        assertThat(r.body()).contains("<StartRowNum>0</StartRowNum>");
    }

    @Test
    void sets_soap_headers() {
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", null, BODY, "100", "0", SECRETS, SECRETS);
        assertThat(r.headers().get("Content-Type")).contains("text/xml");
        assertThat(r.headers().get("SOAPAction")).isEqualTo("\"MyAction\"");
    }

    @Test
    void includes_an_optional_header_template_with_resolved_secret() {
        String header = "<wsse:Security><Password>{{secret:SIEBEL_PWD}}</Password></wsse:Security>";
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", header, BODY, "100", "0", SECRETS, SECRETS);
        assertThat(r.body()).contains("<soap:Header>");
        assertThat(r.body()).contains("<Password>s3cr3t</Password>");
    }

    /** secret: reads the registry-backed resolver, env: only the environment one (#1246). */
    @Test
    void secret_and_env_placeholders_each_read_their_own_resolver() {
        UnaryOperator<String> env = name -> "SIEBEL_PWD".equals(name) ? "from-env" : null;
        String header = "<A>{{secret:SIEBEL_PWD}}</A><B>{{env:SIEBEL_PWD}}</B>";
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", header, BODY, "100", "0", SECRETS, env);
        assertThat(r.body()).contains("<A>s3cr3t</A><B>from-env</B>");
    }

    @Test
    void omits_the_header_element_when_no_header_template() {
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", null, BODY, "100", "0", SECRETS, SECRETS);
        assertThat(r.body()).doesNotContain("<soap:Header>");
    }

    @Test
    void unresolved_secret_fails_fast_without_leaving_a_literal_or_leaking() {
        String header = "<P>{{secret:MISSING}}</P>";
        assertThatThrownBy(() -> SoapRequestBuilder.render(
                "http://h", "/svc", "A", header, BODY, "100", "0", SECRETS, SECRETS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MISSING");
    }

    @Test
    void secret_value_with_regex_metachars_is_inserted_verbatim() {
        // $ and \ are regex-replacement metacharacters; quoteReplacement must keep them literal.
        UnaryOperator<String> metaSecret =
            name -> "SIEBEL_PWD".equals(name) ? "p$a\\b" : null;
        String header = "<wsse:Security><Password>{{secret:SIEBEL_PWD}}</Password></wsse:Security>";
        RestRequest r = SoapRequestBuilder.render(
            "http://h", "/svc", "MyAction", header, BODY, "100", "0", metaSecret, metaSecret);
        assertThat(r.body()).contains("<Password>p$a\\b</Password>");
    }

    @Test
    void null_cursor_fails_fast_instead_of_npe() {
        assertThatThrownBy(() -> SoapRequestBuilder.render(
                "http://h", "/svc", "MyAction", null, BODY, "100", null, SECRETS, SECRETS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cursor");
    }

    @Test
    void xml_special_chars_in_secret_value_are_escaped() {
        // A secret containing & or < would produce malformed XML — and enable XML injection.
        UnaryOperator<String> secrets = name -> "PASS".equals(name) ? "p&<>\"'w" : null;
        String header = "<wsse:Password>{{secret:PASS}}</wsse:Password>";
        RestRequest r = SoapRequestBuilder.render("http://h", "/svc", "A", header, BODY, "10", "0", secrets, secrets);
        assertThat(r.body()).contains("<wsse:Password>p&amp;&lt;&gt;&quot;&apos;w</wsse:Password>");
        assertThat(r.body()).doesNotContain("p&<>\"'w");
    }

    @Test
    void hyphenated_placeholder_name_is_substituted_not_sent_literal() {
        // Env vars like MY-TOKEN contain hyphens — the TOKEN regex must match them so the
        // fail-fast "unresolved placeholder" guard applies (not silently sent on the wire).
        UnaryOperator<String> secrets = name -> "MY-TOKEN".equals(name) ? "tok" : null;
        String header = "<P>{{secret:MY-TOKEN}}</P>";
        RestRequest r = SoapRequestBuilder.render("http://h", "/svc", "A", header, BODY, "10", "0", secrets, secrets);
        assertThat(r.body()).contains("<P>tok</P>");
        assertThat(r.body()).doesNotContain("{{secret:MY-TOKEN}}");
    }

    @Test
    void unmatched_placeholder_with_chars_outside_token_regex_fails_fast() {
        // A dot in {{env:my.prop}} is outside TOKEN's char class — the placeholder slips through
        // appendTail unchanged and would be sent on the wire. Post-substitution guard must catch it.
        String header = "<P>{{env:my.prop}}</P>";
        assertThatThrownBy(() -> SoapRequestBuilder.render(
                "http://h", "/svc", "A", header, BODY, "10", "0", SECRETS, SECRETS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("{{");
    }

    @Test
    void null_body_template_raises_illegal_state_before_npe() {
        assertThatThrownBy(() -> SoapRequestBuilder.render(
                "http://h", "/svc", "A", null, null, "10", "0", SECRETS, SECRETS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("body");
    }
}
