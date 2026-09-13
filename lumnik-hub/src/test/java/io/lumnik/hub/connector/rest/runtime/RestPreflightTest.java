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
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        preflight.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return request -> request; }
        };
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
}
