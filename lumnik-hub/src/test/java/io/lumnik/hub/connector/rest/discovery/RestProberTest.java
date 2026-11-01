package io.lumnik.hub.connector.rest.discovery;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.InternalCaApi;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.secret.SecretRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.rest.EndpointsBlockValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The probe must sample the connector the operator named.
 *
 * <p>The route that ignored it — and what the request body used to carry instead — is described on
 * {@link RestProber#probe(io.lumnik.hub.connector.domain.Connector, String, String)}. The
 * half that javadoc does not carry is the credential: the old path defaulted to {@code bearer} with
 * an empty token, i.e. presented {@code Authorization: Bearer } to a source expecting Basic.
 *
 * <p>What is asserted here is the connector → request mapping, not the inference downstream: the
 * base URL and the credential both come from {@link io.lumnik.hub.connector.domain.EffectiveConfig}
 * through the run's own rules, so a probe cannot knock on a door a run would not.
 */
class RestProberTest {

    private final RestProber prober = new RestProber();

    /** The last request the prober actually sent, and the credential it asked to be built. */
    private RestRequest sent;
    private AuthSpec presented;

    RestProberTest() {
        prober.inference = new JsonStructureInference();
        prober.llm = new LlmManifestAugmenter();
        prober.secrets = SecretRefs.environmentOnly(name -> null);
        prober.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) {
                presented = spec;
                return request -> request;
            }
        };
        answer(200, "{\"data\":[{\"id\":1,\"name\":\"Ada\"}]}");
    }

    private void answer(int status, String body) {
        prober.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) {
                sent = req;
                return RestResponse.of(status, Map.of(), body);
            }
        };
    }

    private static Connector connector(Map<String, Object> config) {
        Connector c = new Connector();
        c.setName("acme-erp");
        c.setTypeId("rest-generic");
        c.setConfig(config);
        return c;
    }

    /**
     * #1395: a source that writes the credential it received into its refusal — the probe's failure quotes that
     * body (ResponseStatusGuard), so it reaches the caller without the credential.
     */
    @Test
    void aRefusalThatEchoesTheCredentialFailsTheProbeWithoutIt() {
        prober.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) {
                return request -> request.withHeader("Authorization", "Bearer probe-tok-a1b2c3");
            }
        };
        prober.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) {
                return RestResponse.of(401, Map.of(), "{\"seen\":\"" + req.headers().get("Authorization") + "\"}");
            }
        };

        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid")), "/partners", "draft"))
            .hasMessageContaining("Bearer <redacted>")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("probe-tok-a1b2c3"));
    }

    /** The connection block a declarative manifest keeps its base URL in — nothing in the body. */
    @Test
    void theConnectionSuppliesTheBaseUrl() throws Exception {
        prober.probe(connector(Map.of("connection", Map.of("base_url", "http://erp.invalid"))),
                     "/partners", "draft");

        assertThat(sent.url()).isEqualTo("http://erp.invalid/partners");
    }

    /** A flat config is a connection too — the legacy shape the merge rule already covers. */
    @Test
    void aFlatConfigIsAConnectionToo() throws Exception {
        prober.probe(connector(Map.of("base_url", "http://erp.invalid/")), "partners", "draft");

        assertThat(sent.url()).isEqualTo("http://erp.invalid/partners");
    }

    /**
     * The credential is the connector's, read by the run's own rule. The old path built
     * {@code bearer} from a {@code token} in the request body — so a Basic source was probed with
     * an empty Bearer, and the only way to probe at all was to paste a secret into an HTTP body
     * while {@code lm secret} exists.
     */
    @Test
    void theConnectorsOwnCredentialIsPresented() throws Exception {
        prober.probe(connector(Map.of("connection", Map.of(
                         "base_url", "http://erp.invalid",
                         "auth", Map.of("kind", "basic", "username", "erp", "password_env", "ERP_PW")))),
                     "/partners", "draft");

        assertThat(presented.kind()).isEqualTo("basic");
        assertThat(presented.config()).containsEntry("username", "erp")
                                      .containsEntry("password_env", "ERP_PW");
    }

    /**
     * The connector's declared headers go out on the probe too, a {@code value_env} read like the
     * run reads it — lm secret first. A source gated on a header answered the probe 401 otherwise.
     */
    @Test
    void theConnectorsDeclaredHeadersAreSent() throws Exception {
        prober.secrets = new SecretRefs(name -> "CLIENT_ID".equals(name) ? Optional.of("from-registry") : Optional.empty(),
                                        name -> "from-env");

        prober.probe(connector(Map.of("base_url", "http://erp.invalid", "headers", List.of(
                         Map.of("name", "X-Api-Version", "value", "2026-01"),
                         Map.of("name", "X-Client-Id", "value_env", "CLIENT_ID")))),
                     "/partners", "draft");

        assertThat(sent.headers()).containsEntry("X-Api-Version", "2026-01")
                                  .containsEntry("X-Client-Id", "from-registry");
    }

    /** A header reference nothing holds is refused by name, before anything is sent. */
    @Test
    void aHeaderThatDoesNotResolveIsRefusedBeforeAnythingIsSent() {
        prober.secrets = SecretRefs.environmentOnly(name -> null);

        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid", "headers",
                                                  List.of(Map.of("name", "X-Client-Id", "value_env", "CLIENT_ID")))),
                                              "/partners", "draft"))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("Header X-Client-Id unavailable (env=CLIENT_ID)");
        assertThat(sent).isNull();
    }

    /** No auth block means none — the run's default. Not an empty bearer sent to every source. */
    @Test
    void aConnectorWithNoAuthBlockPresentsNone() throws Exception {
        prober.probe(connector(Map.of("base_url", "http://erp.invalid")), "/partners", "draft");

        assertThat(presented.kind()).isEqualTo("none");
        assertThat(presented.config()).isEmpty();
    }

    /**
     * A schema is never inferred from an error body — {@link
     * io.lumnik.hub.connector.rest.ResponseStatusGuard} is the rule, and the message it raises
     * already carries the upstream status and a bounded excerpt of what came back. The resource
     * turns that into a 502; what matters here is that the refusal happens at all, and says which
     * status caused it.
     */
    @Test
    void anUpstreamRefusalIsNamed_notInferredFrom() {
        answer(401, "{\"error\": \"unauthorized\", \"expected_auth\": \"basic\"}");

        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                              "/partners", "draft"))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("expected_auth");
    }

    /**
     * The draft describes the path that was probed, inferred from the body that came back. It is an
     * endpoint FRAGMENT, not a whole manifest — no {@code apiVersion}, no connection: it is meant to
     * be pasted under the {@code endpoints:} of the connector it was probed from, which is why the
     * connector had to be the way in.
     */
    @Test
    void theDraftDescribesThePathItProbed() throws Exception {
        String yaml = prober.probe(connector(Map.of("connection", Map.of("base_url", "http://erp.invalid"))),
                                   "/partners", "draft");

        assertThat(yaml).contains("path: /partners")
                        .contains("table: ext.draft_partners")
                        .contains("{ name: name, path: $.name, type: text }");
    }

    /**
     * Found by the first test ever to walk the probe's green path: a JSON OBJECT whose collection
     * key is not one of the eight {@code JsonStructureInference} knows ({@code data}, {@code items},
     * {@code results}, {@code content}, {@code edges}, {@code nodes}, {@code records}, {@code list})
     * infers no items path at all. The default {@code "$"} then took the dotted-path branch and
     * indexed into a two-character prefix of a one-character string — a
     * {@code StringIndexOutOfBoundsException} the operator read as {@code Internal Server Error}.
     *
     * <p>{@code $} means the document itself. When it is not a collection, the document IS the
     * sample: probing one record's path is a legitimate way to describe its fields. (Since #1650 a
     * lone array of objects under an unknown key is the list — {@code {"partners": [...]}} no longer
     * lands here; an object holding no such array still does.)
     */
    @Test
    void anObjectWithNoKnownCollectionKeyIsDescribedAsItself() throws Exception {
        answer(200, "{\"partner\": {\"id\": 1}, \"tags\": [\"a\"], \"generated_at\": \"2026-09-09\"}");

        String yaml = prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                   "/partners", "draft");

        assertThat(yaml).contains("{ name: generated_at, path: $.generated_at, type: text }");
    }

    /** The other half of {@code $}: a top-level array is described by its first element. */
    @Test
    void theFirstElementOfATopLevelArrayIsTheSample() throws Exception {
        answer(200, "[{\"id\": 7, \"label\": \"Ada\"}]");

        String yaml = prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                   "/partners", "draft");

        assertThat(yaml).contains("unique_key: $.id")
                        .contains("{ name: label, path: $.label, type: text }");
    }

    /**
     * A nested items path is walked segment by segment, the way the SOAP body parser walks one.
     * {@code inferItemsPath} produces {@code $.data.<k>} for an envelope that wraps its collection
     * twice, and that walk must reach the records, not the wrapper.
     */
    @Test
    void aNestedItemsPathIsWalkedToTheRecords() throws Exception {
        answer(200, "{\"data\": {\"items\": [{\"id\": 3, \"label\": \"Ada\"}]}}");

        String yaml = prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                   "/partners", "draft");

        assertThat(yaml).contains("{ name: label, path: $.label, type: text }")
                        .contains("unique_key: $.id");
    }

    /**
     * A connector with no {@code base_url} is refused by name, not by NullPointerException.
     *
     * <p>The apply path could never produce one — {@code RestConnectorType.validateConfig} refuses
     * it — but the flat {@code POST /api/platform/connectors} CRUD door persists a config map
     * without running connector-type validation, so the row exists. {@code Urls.join} then NPE'd
     * and the operator got a 500. Same rule, same message as the apply door: one spelling, so a
     * config refused at the front door cannot be silently accepted at this one.
     */
    @Test
    void aConnectorWithNoBaseUrlIsRefusedByName() {
        assertThatThrownBy(() -> prober.probe(connector(Map.of("connection", Map.of("auth", Map.of("kind", "none")))),
                                              "/partners", "draft"))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("base_url");
    }

    /**
     * A connector of another family is refused BY NAME on the hub too. The CLI guards this, but
     * `lm` is not the only client of the route, and every other family lacks the blocks a REST
     * request is composed from — so the fallback refusal would talk about a missing `base_url` and
     * never say the word jdbc.
     */
    @Test
    void aConnectorOfAnotherFamilyIsRefusedByName() {
        Connector jdbc = connector(Map.of("base_url", "http://erp.invalid"));
        jdbc.setTypeId("jdbc-generic");

        assertThatThrownBy(() -> prober.probe(jdbc, "/partners", "draft"))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("jdbc-generic")
            .hasMessageContaining("rest-generic");
    }

    /**
     * A syntactically invalid {@code base_url} must be refused by name too. Presence is not
     * usability: {@code Urls.join} happily concatenates, and the failure lands much later in
     * {@code URI.create} as an {@code IllegalArgumentException} — which is neither a
     * {@code ConnectorException} nor an {@code IOException}, so it escapes every catch the route
     * has and reaches the operator as {@code Internal Server Error}.
     */
    @Test
    void aBaseUrlThatIsNotAUrlIsRefusedByName() {
        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "not a url")),
                                              "/partners", "draft"))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("base_url");
    }

    /**
     * A credential that does not resolve is refused HERE, before anything is sent.
     *
     * <p>The factories encode what they have — {@code BasicAuthProviderFactory} builds
     * {@code base64("user:")} when {@code password_env} resolves to nothing — and refuse only in
     * {@link AuthProvider#probe()}. The run ({@code RestEndpointReader}) and the pre-flight both
     * call it before their first request; the probe did not, so it knocked with an empty password
     * and reported the source's 401 as an upstream refusal. That inverts the diagnosis: the
     * operator reads "your API rejected us" when the truth is "your secret is not set".
     *
     * <p>The assertion that matters is that NOTHING was sent.
     */
    @Test
    void aCredentialThatDoesNotResolveIsRefusedBeforeAnythingIsSent() {
        prober.authReg = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) {
                return new AuthProvider() {
                    @Override public RestRequest apply(RestRequest r) { return r; }
                    @Override public void probe() {
                        throw new AuthConfigException("Basic password unavailable (env=ACME_BASIC_PW)");
                    }
                };
            }
        };

        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                              "/partners", "draft"))
            .isInstanceOf(AuthConfigException.class)
            .hasMessageContaining("ACME_BASIC_PW");
        assertThat(sent).as("no request may be sent with a credential known to be broken").isNull();
    }

    /**
     * The endpoint path is caller input, and a path that does not compose a usable URL is the
     * caller's mistake. {@code Urls.join} concatenates whatever it is given, so a space survived
     * all the way to {@code URI.create} inside the HTTP client and came back as an uncaught
     * {@code IllegalArgumentException} — a 500 for something the caller can fix.
     */
    @Test
    void anEndpointPathThatCannotComposeAUrlIsRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                              "/part ners", "draft"))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("endpoint path");
        assertThat(sent).as("nothing may be sent for a URL that cannot be composed").isNull();
    }

    /** An empty collection still drafts — with nothing to promote, rather than with a crash. */
    @Test
    void anEmptyCollectionDraftsWithoutPromotes() throws Exception {
        answer(200, "{\"data\": []}");

        String yaml = prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                   "/partners", "draft");

        assertThat(yaml).contains("promote:").doesNotContain("{ name:");
    }

    /**
     * An interrupted request is a cancelled request, not an answer about the source. Caught as a
     * plain exception it would drop the thread's interrupt flag on the floor; the run path settled
     * this in {@code RestEndpointReader.fetchAllPages} and the pre-flight borrowed it — re-interrupt,
     * then fail loudly. A third caller of the same HTTP client owes the same.
     */
    @Test
    void anInterruptedProbeIsRaised_andTheFlagSurvives() {
        prober.http = new RestHttpClient() {
            @Override public RestResponse execute(RestRequest req) throws Exception {
                throw new InterruptedException("cancelled");
            }
        };

        assertThatThrownBy(() -> prober.probe(connector(Map.of("base_url", "http://erp.invalid")),
                                              "/partners", "draft"))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("interrupted");
        // Thread.interrupted() also clears the flag, which keeps it out of the next test.
        assertThat(Thread.interrupted()).as("the interrupt flag must survive the catch").isTrue();
    }

    /** #1609: a connector trusting an internal CA is probed through that trust, as its runs are. */
    @Test
    void aConnectorTrustingAnInternalCa_isProbedThroughThatTrust() throws Exception {
        String pem = "cluster CA";
        InternalCaApi api = new InternalCaApi("{\"items\":[{\"uid\":\"a\"}]}");
        prober.http = api;
        prober.secrets = SecretRefs.environmentOnly(name -> "CLUSTER_CA".equals(name) ? pem : null);

        prober.probe(connector(Map.of("base_url", "https://kubernetes.default.svc",
            "tls", Map.of("ca_secret", "CLUSTER_CA"))), "/api/v1/pods", "draft");

        assertThat(api.trustedPems).containsExactly(pem);
        assertThat(api.sent.url()).isEqualTo("https://kubernetes.default.svc/api/v1/pods");
    }

    /** #1650: Spring PetClinic of before Boot — the whole list at once, under a key the probe did not know. */
    @Test
    void aWholeListUnderAnUnknownKeyIsDraftedAsNoPagesWithItsPathAndColumns() throws Exception {
        answer(200, "{\"vetList\":[{\"id\":1,\"firstName\":\"James\"},{\"id\":3,\"firstName\":\"Linda\"}]}");
        String draft = prober.probe(connector(Map.of("base_url", "http://clinic.invalid/petclinic")), "/vets.json", "clinic");
        assertThat(draft)
            .contains("  pagination: { kind: none, response_path: $.vetList }   "
                + "# 2 items, no paging signal in the answer: if the source pages, declare how\n")
            .contains("- { name: firstname, path: $.firstName, type: text }");
    }

    /**
     * A key that needs bracket notation: read by the run's own reader, and written as a YAML value the hub parses —
     * {@code $['vet list']} bare in a flow map is not YAML. The draft is parsed and held to the per-endpoint contract.
     */
    @Test
    void aListUnderABracketKeyIsDraftedAsYamlTheHubAccepts() throws Exception {
        answer(200, "{\"vet list\":[{\"id\":1,\"Last name\":\"Carter\"}]}");
        String draft = prober.probe(connector(Map.of("base_url", "http://clinic.invalid")), "/vets", "clinic");
        JsonNode endpoint = new YAMLMapper().readTree(draft.lines().filter(l -> !l.startsWith("#")).collect(joining("\n"))).get(0);
        assertThat(endpoint.path("pagination").path("response_path").asText()).isEqualTo("$['vet list']");
        assertThat(endpoint.path("target").path("promote").findValuesAsText("path")).contains("$['Last name']");
        assertThat(ConfigDefRunner.run(EndpointsBlockValidator.perEndpointDef(), endpoint, "spec.endpoints[]")).isEmpty();
    }

    /** A top-level array of scalars is no list of records: no columns, and no crash. */
    @Test
    void aTopLevelArrayOfScalarsDraftsNoColumnsInsteadOfFailing() throws Exception {
        answer(200, "[\"a\",\"b\"]");
        assertThat(prober.probe(connector(Map.of("base_url", "http://erp.invalid")), "/codes", "draft"))
            .contains("  pagination: { kind: unknown }\n")
            .contains("    promote:\n");
    }

    /** A paged envelope the detectors do not name (Django REST) is never drafted as the whole list. */
    @Test
    void aDjangoRestPageIsNotDraftedAsTheWholeList() throws Exception {
        answer(200, "{\"count\":120,\"next\":\"http://erp.invalid/partners?page=2\",\"previous\":null,\"results\":[{\"id\":1}]}");
        assertThat(prober.probe(connector(Map.of("base_url", "http://erp.invalid")), "/partners", "draft"))
            .contains("  pagination: { kind: unknown, response_path: $.results }\n")
            .doesNotContain("kind: none");
    }

    /** An answer whose list the probe cannot find still leaves the kind to the integrator. */
    @Test
    void anAnswerWithNoListStaysUnknown() throws Exception {
        answer(200, "{\"status\":\"ok\"}");
        assertThat(prober.probe(connector(Map.of("base_url", "http://erp.invalid")), "/health", "draft"))
            .contains("  pagination: { kind: unknown }\n");
    }
}
