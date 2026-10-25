package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.auth.OAuth2AuthProviderFactory;
import io.lumnik.hub.connector.rest.auth.OAuth2Tokens;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.secret.SecretRefs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The pre-flight must knock the way the run knocks.
 *
 * <p>Every case here was watched failing on a live bench before it was written down. The old
 * pre-flight composed its own {@code GET base_url + path} out of the CONNECTOR config alone, so it
 * dropped everything an endpoint or a request shape contributes — and answered confidently in both
 * directions: a false red on a healthy connector whose headers it never sent, a false green on a
 * GraphQL endpoint it never actually queried.
 *
 * <p>These are not assertions about the shared composer (which both sides now call, and which
 * therefore cannot disagree with itself). They are assertions about the config → request mapping:
 * whoever re-forks the pre-flight tomorrow turns them red.
 */
class RestPreflightTest {

    private final RestPreflight preflight = new RestPreflight();

    RestPreflightTest() {
        preflight.pagination = new PaginationFactory();
        preflight.secrets = SecretRefs.environmentOnly(name -> null);
    }

    private static ConnectorEndpoint endpoint(String name, String method, String path,
                                              Map<String, Object> config) {
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setName(name);
        ep.setMethod(method);
        ep.setPath(path);
        ep.setConfig(config);
        return ep;
    }

    private static Connector connector(Map<String, Object> config) {
        Connector c = new Connector();
        c.setName("acme");
        c.setTypeId("rest-generic");
        c.setConfig(config);
        return c;
    }

    /** An auth registry whose every provider signs nothing: the request goes out as composed. */
    private static AuthProviderRegistry noAuth() {
        return new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return request -> request; }
        };
    }

    /** The offset endpoint of the bench's `acme-headers`: pagination lives on the ENDPOINT. */
    private static ConnectorEndpoint offsetEndpoint() {
        return endpoint("partners", "GET", "/hdr/partners",
            Map.of("pagination", Map.of("kind", "offset",
                                        "offset_param", "offset",
                                        "limit_param", "limit",
                                        "limit", "5000",
                                        "total_path", "$.total",
                                        "response_path", "$.data")));
    }

    /** The bench's `acme-graphql`: the row says GET with no path, the request is a POST. */
    private static ConnectorEndpoint graphQlEndpoint() {
        return endpoint("partners", "GET", "",
            Map.of("kind", "graphql",
                   "query", "query { partners { id } }",
                   "pagination", Map.of("kind", "relay",
                                        "cursor_binding", "variable",
                                        "cursor_var", "after",
                                        "cursor_path", "$.data.partners.pageInfo.endCursor",
                                        "has_more_path", "$.data.partners.pageInfo.hasNextPage",
                                        "response_path", "$.data.partners.edges[*].node")));
    }

    private static Connector graphQlConnector() {
        return connector(Map.of("base_url", "http://erp.invalid", "graphql_path", "/graphql"));
    }

    /**
     * The false red, exactly as the bench showed it: `acme-headers` runs Completed, and its
     * pre-flight answered 400 "missing or wrong X-Api-Version" — because declarative headers are
     * connector-level config the old composer never read.
     */
    @Test
    void carriesTheDeclarativeHeadersTheRunSends() {
        Connector c = connector(Map.of(
            "base_url", "http://erp.invalid",
            "headers", List.of(Map.of("name", "X-Api-Version", "value", "2024-10"))));

        RestRequest req = preflight.request(c, offsetEndpoint());

        assertThat(req.headers()).containsEntry("X-Api-Version", "2024-10");
    }

    /** A header's {@code value_env} resolves the way the run resolves it: lm secret first, then the environment. */
    @Test
    void resolvesAHeaderReferenceThroughTheSecretRegistry() {
        preflight.secrets = new SecretRefs(
            name -> "CLIENT_ID".equals(name) ? Optional.of("from-registry") : Optional.empty(), name -> "from-env");
        Connector c = connector(Map.of(
            "base_url", "http://erp.invalid",
            "headers", List.of(Map.of("name", "X-Client-Id", "value_env", "CLIENT_ID"))));

        RestRequest req = preflight.request(c, offsetEndpoint());

        assertThat(req.headers()).containsEntry("X-Client-Id", "from-registry");
    }

    /** A header reference nothing holds is the verdict, named, and nothing is sent. */
    @Test
    void anUnresolvedHeaderReferenceIsTheVerdict() {
        preflight.authReg = noAuth();
        preflight.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) { throw new AssertionError("nothing may be sent"); }
        };
        Connector c = connector(Map.of(
            "base_url", "http://erp.invalid",
            "headers", List.of(Map.of("name", "X-Client-Id", "value_env", "CLIENT_ID"))));

        RestPreflight.Verdict refused = preflight.check(c, offsetEndpoint());

        assertThat(refused.ok()).isFalse();
        assertThat(refused.detail()).isEqualTo("Header X-Client-Id unavailable (env=CLIENT_ID)");
    }

    /**
     * The false green: a GraphQL endpoint declares no path and is POSTed to `graphql_path` with the
     * query as its body. The old composer GET-ed `base_url + ""` and cheerfully reported 200 on the
     * server's index banner — a page the run never reads.
     */
    @Test
    void usesTheRequestShapeOfAGraphQlEndpoint() {
        RestRequest req = preflight.request(graphQlConnector(), graphQlEndpoint());

        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.url()).isEqualTo("http://erp.invalid/graphql");
        assertThat(req.body()).contains("partners");
    }

    /**
     * A declarative manifest keeps its connection under `connection:`. Reading the connector config
     * flat found no `base_url` at all there, so the pre-flight knocked on the empty string.
     */
    @Test
    void unwrapsTheConnectionBlockLikeARunDoes() {
        Connector c = connector(Map.of(
            "connection", Map.of("base_url", "http://erp.invalid")));

        RestRequest req = preflight.request(c, offsetEndpoint());

        assertThat(req.url()).startsWith("http://erp.invalid/hdr/partners");
    }

    /**
     * An interrupted request is a cancelled request, not a verdict about the upstream. Reporting it
     * as {@code ok:false} would tell the operator their endpoint refused when in fact nobody ever
     * asked it, and swallowing {@link InterruptedException} into a generic catch drops the thread's
     * interrupt flag on the floor.
     *
     * <p>The run path settled this in {@code RestEndpointReader.fetchAllPages}: re-interrupt, then
     * fail loudly. This borrows that, like everything else here.
     */
    @Test
    void anInterruptedRequestIsRaised_notReportedAsARefusal() {
        preflight.authReg = noAuth();
        preflight.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) throws Exception {
                throw new InterruptedException("cancelled");
            }
        };

        assertThatThrownBy(() -> preflight.check(connector(Map.of("base_url", "http://erp.invalid")),
                                                 offsetEndpoint()))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("interrupted");
        // Thread.interrupted() also clears the flag, which keeps it out of the next test.
        assertThat(Thread.interrupted()).as("the interrupt flag must survive the catch").isTrue();
    }

    /**
     * A Verdict is a wire contract read by a typed Go struct, and it must not carry a null where it
     * carries a string everywhere else. The refusal path — a config the run could not start from —
     * is the one that builds a Verdict without ever composing a request, so it is the one that
     * reaches for "there is nothing to report here".
     *
     * <p>Checked over the record's components rather than field by field: the next String added to
     * Verdict is covered the day it is added, without anyone remembering to come back here.
     */
    @Test
    void noStringComponentOfARefusalVerdictIsNull() throws Exception {
        ConnectorEndpoint ep = endpoint("partners", "GET", "/x",
            Map.of("pagination", Map.of("kind", "no-such-strategy")));

        RestPreflight.Verdict refused =
            preflight.check(connector(Map.of("base_url", "http://erp.invalid")), ep);

        assertThat(refused.ok()).isFalse();
        assertThat(refused.detail()).contains("no-such-strategy");
        for (RecordComponent c : RestPreflight.Verdict.class.getRecordComponents()) {
            if (c.getType() != String.class) continue;
            assertThat(c.getAccessor().invoke(refused))
                .as("Verdict.%s must be a string, not null, even when nothing was sent", c.getName())
                .isNotNull();
        }
    }

    /**
     * When the credential is what fails, the request has already been composed — so the verdict
     * must carry the shape a run would have sent, not the shape of the endpoint row.
     *
     * <p>A GraphQL endpoint is the case that shows it: its row says {@code GET} with an empty path,
     * while the request the run composes is a {@code POST} to {@code graphql_path}. Reporting the
     * row here would print GET for an endpoint this very command POSTs to — the exact gap this
     * class was written to close, left behind on the one path where it matters most. An auth probe
     * fails when a {@code value_env} does not resolve, which is precisely the situation the
     * operator runs a pre-flight to diagnose.
     */
    @Test
    void anAuthFailureStillReportsTheRequestThatWasComposed() {
        preflight.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) {
                throw new IllegalStateException("secret ACME_API_KEY is not set");
            }
        };

        RestPreflight.Verdict refused = preflight.check(graphQlConnector(), graphQlEndpoint());

        assertThat(refused.ok()).isFalse();
        assertThat(refused.detail()).contains("ACME_API_KEY");
        assertThat(refused.method()).as("the method the run would send, not the endpoint row's")
            .isEqualTo("POST");
        assertThat(refused.url()).as("the URL was known — hiding it helps nobody")
            .isEqualTo("http://erp.invalid/graphql");
    }

    /**
     * A request that never got an answer is reported by its kind, never by the exception's message:
     * a JDK message can quote the source URL — whole, without its userinfo, its path alone,
     * percent-encoded — and that URL is the integrator's, legally carrying a credential (#1164).
     */
    @ParameterizedTest
    @MethodSource("quotingExceptions")
    void anUnreachableSourceIsReportedByItsKind_neverByTheMessage(Exception thrown, String expected) {
        preflight.authReg = noAuth();
        preflight.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) throws Exception { throw thrown; }
        };

        RestPreflight.Verdict refused =
            preflight.check(connector(Map.of("base_url", "http://erp.invalid")), offsetEndpoint());

        assertThat(refused.ok()).isFalse();
        assertThat(refused.detail()).isEqualTo(expected).doesNotContain("user", "pw", "ECRET", "k=v");
    }

    private static final String SOURCE = "https://user:pw@api.example/p/SECRET?k=v";

    static Stream<Arguments> quotingExceptions() {
        return Stream.of(
            arguments(new ConnectException("Connection refused: " + SOURCE), "connection failed (ConnectException)"),
            arguments(new HttpTimeoutException("request timed out: /p/SECRET?k=v"), "timeout (HttpTimeoutException)"),
            arguments(new IOException("https://api.example/p/%53ECRET?k%3Dv", new SSLHandshakeException(SOURCE)),
                      "TLS failure (IOException)"));
    }

    /**
     * And a real JDK failure, thrown before any I/O: a space in the composed URL makes
     * {@code URI.create} inside the real client refuse it, with the whole URL in its message.
     */
    @Test
    void aRealJdkRefusalOfTheUrlIsReportedByItsKind() {
        preflight.authReg = noAuth();
        preflight.http = new RestHttpClient();

        RestPreflight.Verdict refused = preflight.check(
            connector(Map.of("base_url", "https://user:pw@api.example")),
            endpoint("partners", "GET", "/p/SECRET?k=v w", Map.of("pagination", Map.of("kind", "link-header"))));

        assertThat(refused.detail()).isEqualTo("invalid URL (IllegalArgumentException)");
    }

    /**
     * The URL is shown on purpose — but through {@code Urls.forDisplay}: userinfo in {@code base_url}
     * and a token in the endpoint path's query would otherwise reach the CLI verbatim (#1166).
     */
    @Test
    void theReportedUrlKeepsItsHostAndPathButNeverItsCredential() {
        preflight.authReg = noAuth();
        preflight.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) throws Exception {
                throw new ConnectException("Connection refused");
            }
        };

        RestPreflight.Verdict refused = preflight.check(
            connector(Map.of("base_url", "https://user:pw@api.example")),
            endpoint("partners", "GET", "/p/orders?k=v", Map.of("pagination", Map.of("kind", "link-header"))));

        assertThat(refused.url()).isEqualTo("https://…@api.example/p/orders");
    }

    /** Nothing composed, nothing to report: no request shape exists to be honest about. */
    @Test
    void aRequestThatCouldNotBeComposedReportsNoUrl() {
        ConnectorEndpoint ep = endpoint("partners", "GET", "/x",
            Map.of("pagination", Map.of("kind", "no-such-strategy")));

        RestPreflight.Verdict refused =
            preflight.check(connector(Map.of("base_url", "http://erp.invalid")), ep);

        assertThat(refused.method()).isEqualTo("GET");
        assertThat(refused.url()).isEmpty();
        assertThat(refused.detail()).contains("no-such-strategy");
    }

    /**
     * No auth block means `none` — the rule the run has always applied. The old pre-flight invented
     * `bearer` with an empty token instead, sending a bare `Authorization:` header the run never
     * sends: a different credential, on a connector declared to need none.
     */
    @Test
    void anAbsentAuthBlockMeansNoneNotAnEmptyBearer() {
        Connector c = connector(Map.of("base_url", "http://erp.invalid"));

        assertThat(preflight.authSpec(c, offsetEndpoint()).kind()).isEqualTo("none");
    }

    /** And `kind` is a selector, not a credential: the run strips it out of the config it passes on. */
    @Test
    void theAuthKindIsNotAlsoPassedAsCredentialConfig() {
        Connector c = connector(Map.of(
            "base_url", "http://erp.invalid",
            "auth", Map.of("kind", "basic", "username", "demo-user", "password_env", "ACME_BASIC_PW")));

        var spec = preflight.authSpec(c, offsetEndpoint());

        assertThat(spec.kind()).isEqualTo("basic");
        assertThat(spec.config()).containsEntry("username", "demo-user").doesNotContainKey("kind");
    }

    /**
     * Review of #1394: a source that echoes the Authorization header in its 401 body. After the renewal the second
     * 401 carried the fresh bearer into the verdict's sample — what `lm source rest test` prints.
     */
    @Test
    void anOAuth2TokenEchoedInARefusalNeverReachesTheSample() {
        OAuth2Tokens tokens = new OAuth2Tokens();
        RestHttpClient echoing = new RestHttpClient() {
            int issued;
            @Override public RestResponse execute(RestRequest r) {
                if (r.url().endsWith("/oauth/token")) {
                    issued++;
                    return RestResponse.of(200, Map.of(), "{\"access_token\":\"tok-" + issued + "-abcdef\"}");
                }
                return RestResponse.of(401, Map.of(), "{\"authorization\":\"" + r.headers().get("Authorization") + "\"}");
            }
        };
        OAuth2AuthProviderFactory oauth2 =
            new OAuth2AuthProviderFactory(SecretRefs.environmentOnly(n -> "s3cret"), tokens, echoing);
        preflight.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return oauth2.build(spec); }
        };
        preflight.http = echoing;
        Connector c = connector(Map.of("base_url", "http://erp.invalid", "auth", Map.of("kind", "oauth2",
            "token_url", "http://erp.invalid/oauth/token", "client_id", "lumnik", "client_secret_env", "S")));

        RestPreflight.Verdict verdict = preflight.check(c, offsetEndpoint());

        assertThat(verdict.status()).isEqualTo(401);
        assertThat(verdict.sample()).contains("Bearer <redacted>").doesNotContain("tok-1-abcdef", "tok-2-abcdef");
    }
}
