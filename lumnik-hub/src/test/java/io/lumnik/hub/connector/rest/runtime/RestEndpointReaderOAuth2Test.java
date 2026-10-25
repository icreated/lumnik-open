package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.auth.OAuth2AuthProviderFactory;
import io.lumnik.hub.connector.rest.auth.OAuth2Tokens;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #1393: a run reads a source behind OAuth2 client credentials, chunk after chunk, through a token's expiry. */
class RestEndpointReaderOAuth2Test {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final String TOKEN_URL = "https://auth.saas.test/oauth/token";

    /** The SaaS and its authorization server; {@link #revoked} tokens answer 401, as after a key rotation. */
    static final class Saas extends RestHttpClient {
        final List<RestRequest> calls = new ArrayList<>();
        final Set<String> revoked = new HashSet<>();
        int issued;
        /** A source that writes the Authorization header it received into its 401 body. */
        boolean echoes;
        /** A GraphQL error message that quotes the Authorization header, on a 200. */
        boolean graphQlEchoes;
        /** A SOAP Fault whose faultstring quotes the Authorization header, on a 200: only the Fault validator reads it. */
        boolean soapFaultEchoes;
        /** Tokens as short as RFC 6749 allows: it sets no minimum. */
        boolean shortTokens;
        @Override public RestResponse execute(RestRequest req) {
            calls.add(req);
            if (req.url().equals(TOKEN_URL)) {
                issued++;
                return RestResponse.of(200, Map.of(), "{\"access_token\":\"t" + issued + (shortTokens ? "" : "-a1b2c3d4") + "\",\"expires_in\":3600}");
            }
            if (graphQlEchoes) return RestResponse.of(200, Map.of(),
                "{\"errors\":[{\"message\":\"invalid " + req.headers().get("Authorization") + "\"}]}");
            if (soapFaultEchoes) return RestResponse.of(200, Map.of(), "<Envelope><Body><Fault><faultstring>denied "
                + req.headers().get("Authorization") + "</faultstring></Fault></Body></Envelope>");
            if (echoes) return RestResponse.of(401, Map.of(), "{\"seen\":\"" + req.headers().get("Authorization") + "\"}");
            if (revoked.contains(req.headers().get("Authorization"))) return RestResponse.of(401, Map.of(), "{}");
            int p = Integer.parseInt(req.queryParams().getOrDefault("p", "0"));
            return RestResponse.of(200, Map.of(), "{\"page\": " + p + "}");
        }
        List<RestRequest> pages() {
            return calls.stream().filter(r -> !r.url().equals(TOKEN_URL)).toList();
        }
    }

    private final Saas saas = new Saas();
    private final OAuth2Tokens tokens = new OAuth2Tokens();
    private final List<AuthSpec.Owner> owners = new ArrayList<>();
    private final RestEndpointReaderChunkingTest.StatelessPagePagination pagination =
        new RestEndpointReaderChunkingTest.StatelessPagePagination();

    private RestEndpointReader reader() {
        OAuth2AuthProviderFactory oauth2 =
            new OAuth2AuthProviderFactory(SecretRefs.environmentOnly(n -> "s3cret"), tokens, saas);
        AuthProviderRegistry registry = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) {
                owners.add(spec.owner());
                return oauth2.build(spec);
            }
        };
        return new RestEndpointReader(new ConnectorEndpoint(), registry, pagination, new RateLimiterRegistry(), saas,
            new ObjectMapper(), SecretRefs.environmentOnly(name -> null));
    }

    private static ConnectorContext ctx() {
        return ctx(Map.of());
    }

    private static ConnectorContext ctx(Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(extra);
        cfg.put("base_url", "https://saas.test");
        cfg.put("auth", Map.of("kind", "oauth2", "token_url", TOKEN_URL, "client_id", "lumnik", "client_secret_env", "S"));
        return new ConnectorContext(RUN, CONNECTOR, UUID.randomUUID(), 42L, "saas", "rest-generic",
            cfg, new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
    }

    private void sixPagesOfTwo() {
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        for (int p = 0; p < 6; p++) pages.add(List.of(Map.of("id", 2 * p), Map.of("id", 2 * p + 1)));
        pagination.pages = pages;
    }

    /** Every id read from {@code from} on, chunk after chunk, a fresh reader each time as the engine builds one. */
    private List<Object> readAll(ConnectorCursor from) {
        List<Object> ids = new ArrayList<>();
        ReadResult<Map<String, Object>> r = reader().read(4, from, ctx());
        while (r instanceof ReadResult.HasMore<Map<String, Object>> more) {
            more.records().forEach(m -> ids.add(m.get("id")));
            r = reader().read(4, more.nextCursor(), ctx());
        }
        return ids;
    }

    @Test
    void the_run_asks_for_one_token_whatever_the_number_of_chunks_and_names_its_connector() {
        sixPagesOfTwo();
        assertThat(readAll(null)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(saas.issued).isEqualTo(1);
        assertThat(saas.pages()).extracting(r -> r.headers().get("Authorization")).containsOnly("Bearer t1-a1b2c3d4");
        assertThat(owners).isNotEmpty().allMatch(o -> o.tenantId() == 42L && o.connectorId().equals(CONNECTOR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_token_revoked_mid_run_is_renewed_once_and_the_page_read_again() {
        sixPagesOfTwo();
        var first = (ReadResult.HasMore<Map<String, Object>>) reader().read(4, null, ctx());
        saas.revoked.add("Bearer t1-a1b2c3d4");   // the SaaS rotated its keys between two chunks

        List<Object> ids = new ArrayList<>();
        first.records().forEach(m -> ids.add(m.get("id")));
        ids.addAll(readAll(first.nextCursor()));
        assertThat(ids).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(saas.issued).isEqualTo(2);
        List<RestRequest> pages = saas.pages();
        RestRequest refused = pages.get(2);
        RestRequest replayed = pages.get(3);
        assertThat(refused.headers().get("Authorization")).isEqualTo("Bearer t1-a1b2c3d4");
        assertThat(replayed.headers().get("Authorization")).isEqualTo("Bearer t2-a1b2c3d4");
        assertThat(replayed.queryParams()).isEqualTo(refused.queryParams());
    }

    @Test
    void a_refusal_that_echoes_the_token_fails_the_run_without_it() {
        // Review of #1394: the second 401's body reached the run's failure text with the renewed bearer in it.
        sixPagesOfTwo();
        saas.echoes = true;
        assertThatThrownBy(() -> reader().read(4, null, ctx()))
            .hasMessageContaining("upstream returned HTTP 401")
            .hasMessageContaining("Bearer <redacted>")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Bearer t1-a1b2c3d4", "Bearer t2-a1b2c3d4"));
    }

    @Test
    void a_graphql_error_quoting_the_token_fails_the_run_without_it() {
        // Security review of #1394: the GraphQL validator runs inside the retries and quotes the error message —
        // before any redaction the provider could do on the response it gets back.
        sixPagesOfTwo();
        saas.graphQlEchoes = true;
        assertThatThrownBy(() -> reader().read(4, null, ctx(Map.of("kind", "graphql", "query", "{ orders { id } }"))))
            .satisfies(e -> assertThat(e.getMessage()).contains("<redacted>").doesNotContain("Bearer t1-a1b2c3d4"));
    }

    @Test
    void a_soap_fault_quoting_the_token_fails_the_run_without_it() {
        // #1395: the SOAP Fault validator quotes the faultstring into the run's failure, as GraphQL quotes its error.
        sixPagesOfTwo();
        saas.soapFaultEchoes = true;
        assertThatThrownBy(() -> reader().read(4, null, ctx(Map.of("kind", "soap"))))
            .hasMessageContaining("SOAP Fault: denied Bearer <redacted>")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("t1-a1b2c3d4"));
    }

    @Test
    void an_oauth2_token_too_short_to_tell_from_data_fails_the_run_before_any_page() {
        sixPagesOfTwo();
        saas.shortTokens = true;
        assertThatThrownBy(() -> reader().read(4, null, ctx()))
            .hasMessage("OAuth2 access_token is shorter than 8 characters: too short to keep out of the source's responses");
        assertThat(saas.pages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 12345678})
    @SuppressWarnings("unchecked")
    void a_credential_equal_to_a_business_value_leaves_the_rows_intact(long token) {
        // Reviews of #1394: a token equal to a value of the rows — "1", and "12345678", which oauth2 accepts —
        // and a 200: the rows come back exactly as the source wrote them, never transformed.
        RestHttpClient source = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) {
                return RestResponse.of(200, Map.of(), "{\"data\":[{\"id\":" + token + ",\"quantity\":" + token + "}]}");
            }
        };
        PaginationFactory bodyItems = new PaginationFactory() {
            @Override public PaginationStrategy build(Map<String, Object> cfg) {
                return new PaginationStrategy() {
                    @Override public RestRequest first(EndpointConfig c, Object incremental) {
                        return RestRequest.get("https://erp.test/orders");
                    }
                    @Override public List<Map<String, Object>> items(RestResponse resp) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        resp.json().path("data").forEach(n -> rows.add(Map.of("id", n.path("id").asLong(),
                            "quantity", n.path("quantity").asLong())));
                        return rows;
                    }
                    @Override public RestRequest next(RestResponse resp, RestRequest current) { return null; }
                };
            }
        };
        AuthProviderRegistry bearerOne = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return r -> r.withHeader("Authorization", "Bearer " + token); }
        };
        var read = new RestEndpointReader(new ConnectorEndpoint(), bearerOne, bodyItems, new RateLimiterRegistry(), source,
            new ObjectMapper(), SecretRefs.environmentOnly(name -> null)).read(10, null, ctx());
        assertThat(((ReadResult.HasMore<Map<String, Object>>) read).records())
            .containsExactly(Map.of("id", token, "quantity", token));
    }
}
