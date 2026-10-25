package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** #1393: the client-credentials request and how its answer is read. */
class OAuth2GrantTest {

    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private static final String SECRET = "s3cr&t=+/ é";

    private static OAuth2Grant grant(String... kv) {
        Map<String, String> cfg = new HashMap<>(Map.of("token_url", "https://auth.example/oauth/token",
            "client_id", "lumnik orders", "client_secret_env", "SAAS_SECRET"));
        for (int i = 0; i < kv.length; i += 2) cfg.put(kv[i], kv[i + 1]);
        return new OAuth2Grant(cfg, SECRET);
    }

    private static OAuth2Token token(String json) throws Exception {
        return OAuth2Grant.token(new ObjectMapper().readTree(json), NOW);
    }

    @Test
    void basic_client_auth_form_encodes_each_half_before_base64_and_keeps_the_secret_out_of_the_body() {
        RestRequest req = grant("scope", "orders.read orders:write").tokenRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(req.body()).isEqualTo("grant_type=client_credentials&scope=orders.read+orders%3Awrite");
        String basic = new String(Base64.getDecoder().decode(req.headers().get("Authorization").substring(6)),
            StandardCharsets.UTF_8);
        assertThat(basic).isEqualTo("lumnik+orders:s3cr%26t%3D%2B%2F+%C3%A9");
    }

    @Test
    void post_client_auth_puts_the_credentials_in_the_encoded_body() {
        RestRequest req = grant("client_auth", "post").tokenRequest();
        assertThat(req.headers()).doesNotContainKey("Authorization");
        assertThat(req.body()).isEqualTo(
            "grant_type=client_credentials&client_id=lumnik+orders&client_secret=s3cr%26t%3D%2B%2F+%C3%A9");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"access_token\":\"t-long-enough\",\"token_type\":\"Bearer\",\"expires_in\":3600}    | 2026-10-23T08:59:00Z",
        "{\"access_token\":\"t-long-enough\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}| 2026-10-23T08:59:00Z",
        "{\"access_token\":\"t-long-enough\",\"expires_in\":30}                                | 2026-10-23T08:00:27Z",
        "{\"access_token\":\"t-long-enough\"}                                                  | 2026-10-23T08:04:30Z",
        "{\"access_token\":\"t-long-enough\",\"expires_in\":\"soon\"}                          | 2026-10-23T08:04:30Z",
        "{\"access_token\":\"t-long-enough\",\"expires_in\":-5}                                | 2026-10-23T08:04:30Z",
    })
    void a_token_is_renewed_a_tenth_of_its_lifetime_early_at_most_a_minute_and_an_unstated_one_lives_300_s(
            String body, String renewAt) throws Exception {
        assertThat(token(body).renewAt()).isEqualTo(Instant.parse(renewAt));
    }

    @Test
    void a_response_with_no_token_or_another_token_type_is_refused() {
        assertThatThrownBy(() -> token("{\"token_type\":\"Bearer\"}"))
            .isInstanceOf(AuthConfigException.class).hasMessage("OAuth2 token response carries no access_token");
        assertThatThrownBy(() -> token("{\"access_token\":\"  \"}"))
            .isInstanceOf(AuthConfigException.class).hasMessage("OAuth2 token response carries no access_token");
        assertThatThrownBy(() -> token("{\"access_token\":\"t-long-enough\",\"token_type\":\"mac\"}"))
            .isInstanceOf(AuthConfigException.class).hasMessage("OAuth2 token_type is not Bearer");
    }

    @Test
    void a_token_too_short_to_tell_from_data_is_refused() {
        // Reviews of #1394: a bare 6-character token echoed back cannot be told from a value of the rows.
        assertThatThrownBy(() -> token("{\"access_token\":\"tok123\"}"))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("OAuth2 access_token is shorter than 8 characters: too short to keep out of the source's responses");
    }

    @Test
    void a_refusal_names_the_status_and_a_well_formed_error_code_never_the_description() {
        assertThat(OAuth2Grant.refusal(RestResponse.of(401, Map.of(),
            "{\"error\":\"invalid_client\",\"error_description\":\"client lumnik orders secret s3cr unknown\"}")))
            .isEqualTo("OAuth2 token refused by the authorization server: HTTP 401 invalid_client");
        assertThat(OAuth2Grant.refusal(RestResponse.of(400, Map.of(), "{\"error\":\"bad <script> s3cr\"}")))
            .isEqualTo("OAuth2 token refused by the authorization server: HTTP 400");
        assertThat(OAuth2Grant.refusal(RestResponse.of(503, Map.of(), "<html>down</html>")))
            .isEqualTo("OAuth2 token refused by the authorization server: HTTP 503");
    }

    @Test
    void the_fingerprint_and_the_text_carry_no_secret() {
        assertThat(grant().fingerprint()).doesNotContain(SECRET).contains("SAAS_SECRET");
        assertThat(grant().toString()).doesNotContain(SECRET);
        assertThat(grant("scope", "a").fingerprint()).isNotEqualTo(grant("scope", "b").fingerprint());
        assertThat(grant("client_auth", "post").fingerprint()).isNotEqualTo(grant().fingerprint());
    }

    @Test
    void what_is_wrong_before_anything_is_sent_names_the_key_never_the_value() {
        assertThat(grant().whyNotUsable()).isEmpty();
        assertThat(grant("token_url", "ftp://auth.example/t").whyNotUsable())
            .contains("OAuth2 token_url must be an http or https URL");
        assertThat(grant("token_url", "not a url").whyNotUsable()).get().asString().startsWith("OAuth2 token_url must");
        assertThat(grant("client_auth", "jwt").whyNotUsable()).contains("OAuth2 client_auth must be basic or post");
        assertThat(new OAuth2Grant(Map.of("token_url", "https://a.example/t", "client_id", "c",
            "client_secret_env", "SAAS_SECRET"), null).whyNotUsable())
            .contains("OAuth2 client secret unavailable (env=SAAS_SECRET)");
        // Copilot on #1394: a row stored without validation names the missing key, not "env=null".
        assertThat(new OAuth2Grant(Map.of("token_url", "https://a.example/t", "client_id", "c"), null).whyNotUsable())
            .contains("OAuth2 client_secret_env is required");
    }
}
