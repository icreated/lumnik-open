package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #1393: the oauth2 kind as a run, a pre-flight and a probe use it. */
class OAuth2AuthProviderFactoryTest {

    private static final String SECRET = "the-client-secret";
    private static final Map<String, String> CFG = Map.of("token_url", "https://auth.example/oauth/token",
        "client_id", "lumnik", "client_secret_env", "SAAS_SECRET");

    /** The authorization server: hands out t1, t2, … or answers what {@link #refuse} says. */
    static final class TokenServer extends RestHttpClient {
        final List<RestRequest> asked = new ArrayList<>();
        RestResponse refuse;
        Exception unreachable;
        @Override public RestResponse execute(RestRequest req) throws Exception {
            asked.add(req);
            if (unreachable != null) throw unreachable;
            if (refuse != null) return refuse;
            return RestResponse.of(200, Map.of(),
                "{\"access_token\":\"t" + asked.size() + "-abcdefgh\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        }
    }

    private final TokenServer server = new TokenServer();
    private final OAuth2Tokens tokens = new OAuth2Tokens();
    private final UUID connector = UUID.randomUUID();

    private AuthProvider provider(String secret) {
        SecretRefs secrets = SecretRefs.environmentOnly(n -> "SAAS_SECRET".equals(n) ? secret : null);
        return new OAuth2AuthProviderFactory(secrets, tokens, server)
            .build(new AuthSpec("oauth2", CFG, List.of(), new AuthSpec.Owner(7L, connector)));
    }

    private static String bearer(RestRequest r) {
        return r.headers().get("Authorization");
    }

    @Test
    void two_tenants_with_the_same_public_grant_and_two_secrets_each_get_their_own_token() throws Exception {
        SecretRefs first = SecretRefs.environmentOnly(n -> "secret-of-tenant-7");
        SecretRefs second = SecretRefs.environmentOnly(n -> "secret-of-tenant-8");
        List<String> sent = new ArrayList<>();
        AuthProvider.Sender record = r -> { sent.add(bearer(r)); return RestResponse.of(200, Map.of(), "{}"); };
        new OAuth2AuthProviderFactory(first, tokens, server).build(new AuthSpec("oauth2", CFG, List.of(), new AuthSpec.Owner(7L, connector)))
            .send(RestRequest.get("https://saas.example/orders"), record);
        new OAuth2AuthProviderFactory(second, tokens, server).build(new AuthSpec("oauth2", CFG, List.of(), new AuthSpec.Owner(8L, connector)))
            .send(RestRequest.get("https://saas.example/orders"), record);
        assertThat(sent).containsExactly("Bearer t1-abcdefgh", "Bearer t2-abcdefgh");
        assertThat(server.asked).extracting(OAuth2AuthProviderFactoryTest::bearer)
            .containsExactly(basic("secret-of-tenant-7"), basic("secret-of-tenant-8"));
    }

    private static String basic(String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(("lumnik:" + secret).getBytes());
    }

    @Test
    void a_401_renews_the_token_once_and_signs_the_original_request_again() throws Exception {
        List<RestRequest> sent = new ArrayList<>();
        RestRequest original = RestRequest.get("https://saas.example/orders").withQuery("page", "2");
        RestResponse resp = provider(SECRET).send(original, r -> {
            sent.add(r);
            return RestResponse.of(sent.size() == 1 ? 401 : 200, Map.of(), "{}");
        });
        assertThat(resp.status()).isEqualTo(200);
        assertThat(sent).extracting(OAuth2AuthProviderFactoryTest::bearer).containsExactly("Bearer t1-abcdefgh", "Bearer t2-abcdefgh");
        assertThat(sent.get(1).queryParams()).isEqualTo(original.queryParams());
        assertThat(server.asked).hasSize(2);
    }

    @Test
    void a_second_401_is_the_source_s_answer() throws Exception {
        List<RestRequest> sent = new ArrayList<>();
        RestResponse resp = provider(SECRET).send(RestRequest.get("https://saas.example/orders"), r -> {
            sent.add(r);
            return RestResponse.of(401, Map.of(), "{}");
        });
        assertThat(resp.status()).isEqualTo(401);
        assertThat(sent).hasSize(2);
        assertThat(server.asked).hasSize(2);
    }

    @Test
    void a_refused_grant_is_said_at_probe_with_its_code_and_without_the_secret() {
        server.refuse = RestResponse.of(401, Map.of(),
            "{\"error\":\"invalid_client\",\"error_description\":\"bad secret the-client-secret\"}");
        assertThatThrownBy(() -> provider(SECRET).probe())
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("OAuth2 token refused by the authorization server: HTTP 401 invalid_client");
        assertThat(server.asked).hasSize(1);   // refused once, not replayed
    }

    @Test
    void an_unreachable_token_endpoint_is_named_by_its_kind() {
        server.unreachable = new ConnectException("Connection refused: auth.example/10.0.0.9 user:pw");
        assertThatThrownBy(() -> provider(SECRET).probe())
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("OAuth2 token request failed: connection failed (ConnectException)");
    }

    @Test
    void a_missing_secret_is_said_before_anything_is_sent() {
        assertThatThrownBy(() -> provider(null).probe())
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("OAuth2 client secret unavailable (env=SAAS_SECRET)");
        assertThat(server.asked).isEmpty();
    }

    @Test
    void a_credential_with_no_connector_is_not_built() {
        SecretRefs secrets = SecretRefs.environmentOnly(n -> SECRET);
        assertThatThrownBy(() -> new OAuth2AuthProviderFactory(secrets, tokens, server)
            .build(new AuthSpec("oauth2", CFG, List.of())))
            .isInstanceOf(IllegalStateException.class);
    }
}
