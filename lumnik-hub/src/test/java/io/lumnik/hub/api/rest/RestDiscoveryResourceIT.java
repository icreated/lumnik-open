package io.lumnik.hub.api.rest;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The two REST discovery doors, against a real upstream: the hub itself.
 *
 * <p>These assertions used to read {@code anyOf(is(200), is(500))} — a shape that passes whatever
 * the door does, which is how {@code lm source rest probe} stayed broken end to end in three
 * stacked layers without a single red test. The upstream is the hub itself: its {@code /q/openapi}
 * is a real spec, its {@code /q/health} a real JSON body, and any other path a real 404. Nothing is
 * mocked and nothing leaves the machine.
 *
 * <p>The address comes from {@link TestHTTPResource}, never hardcoded: {@code http.test-port=0}
 * means the test port is random, so the old file's {@code localhost:8081} was pointing at nothing
 * at all — which is exactly what its "500 is acceptable when the URL is not resolvable" comment was
 * quietly conceding.
 */
@QuarkusTest
class RestDiscoveryResourceIT {

    @TestHTTPResource("/")
    URL hubRoot;

    /**
     * The injected root's own protocol and authority, never rebuilt from host + ":" + port: an
     * IPv6 literal needs brackets and the scheme is not ours to assume.
     */
    private String hub() {
        return hubRoot.getProtocol() + "://" + hubRoot.getAuthority();
    }

    /** One authenticated JSON request — the four lines every case below opens with. */
    private RequestSpecification json(Map<String, Object> body) {
        return given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json").body(body);
    }

    /** A rest-generic connector written through the FLAT CRUD door, which validates nothing. */
    private String restConnector(Map<String, Object> config) {
        return json(Map.of("tenantId", 1, "name", "probe-it-" + System.nanoTime(),
                           "typeId", "rest-generic", "config", config))
        .when().post("/api/platform/connectors")
        .then().statusCode(201).extract().jsonPath().getString("id");
    }

    /** Its connection points back at the hub, so a probe has a live source. */
    private String restConnectorPointingAtTheHub() {
        return restConnector(Map.of("base_url", hub()));
    }

    // ── from-openapi ────────────────────────────────────────────────────────────

    @Test
    void from_openapi_returns_a_manifest_for_a_reachable_spec() {
        json(Map.of("url", hub() + "/q/openapi", "name", "self", "tags", List.of("internal")))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(200)
            .body(containsString("kind: Connector"))
            .body(containsString("connector_type: rest-generic"));
    }

    @Test
    void from_openapi_returns_400_when_url_missing() {
        json(Map.of("name", "myconnector"))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(400);
    }

    /**
     * A url that is not a URL is the CALLER's mistake — 400 — not the upstream's 502. The two are
     * different diagnoses and the operator acts on them differently: fix your command, or go look
     * at the source. Same structural rule as the manifest door, through {@code Urls.whyNotAbsolute}.
     */
    @Test
    void from_openapi_returns_400_when_the_url_is_not_a_url() {
        json(Map.of("url", "not a url", "name", "nope"))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(400)
            .body("error", containsString("url"));

        json(Map.of("url", "example.com/spec.json", "name", "nope"))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(400);
    }

    /**
     * A spec URL that holds no spec is the upstream's problem, not the hub's, and it used to reach
     * the operator as a bare 500 plus an error id. Measured, not assumed: {@code OpenAPIV3Parser}
     * raises from inside itself (an NPE on its own null message list) rather than answering null —
     * see {@code OpenApiImporterTest#a_url_that_holds_no_spec_is_refused_by_name}.
     */
    @Test
    void from_openapi_returns_502_naming_the_spec_it_could_not_read() {
        json(Map.of("url", hub() + "/q/no-such-spec", "name", "nope"))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(502)
            .body("error", containsString("no-such-spec"));
    }

    // ── probe ───────────────────────────────────────────────────────────────────

    /** The connector IS the request now: no connector, nothing to probe. */
    @Test
    void probe_returns_400_when_connectorId_missing() {
        json(Map.of("endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(400)
            .body("error", containsString("connectorId"));
    }

    @Test
    void probe_returns_400_when_connectorId_is_not_a_uuid() {
        json(Map.of("connectorId", "not-a-uuid", "endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(400)
            .body("error", containsString("invalid UUID"));
    }

    /**
     * A body field of the wrong TYPE is the caller's mistake, not a hub defect. Casting it blind
     * ({@code (String) body.get(...)}, {@code (List<String>) body.get("tags")}) raises a
     * ClassCastException the operator reads as {@code Internal Server Error} — the failure this
     * whole branch closes, left standing on the way in.
     */
    @Test
    void a_body_field_of_the_wrong_type_is_refused_not_crashed() {
        json(Map.of("connectorId", 123, "endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(400);

        json(Map.of("url", hub() + "/q/openapi", "name", "self", "tags", "internal"))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(400)
            .body("error", containsString("tags"));

        json(Map.of("url", hub() + "/q/openapi", "name", "self", "tags", List.of(1, 2)))
        .when().post("/api/platform/sources/rest/from-openapi")
        .then().statusCode(400)
            .body("error", containsString("tags"));
    }

    @Test
    void probe_returns_404_for_a_connector_that_does_not_exist() {
        json(Map.of("connectorId", UUID.randomUUID().toString(), "endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(404)
            .body("error", containsString("not found"));
    }

    /**
     * The green path, which no test has ever exercised: a live 2xx JSON body becomes an endpoint
     * fragment naming the path that was probed.
     */
    @Test
    void probe_drafts_an_endpoint_fragment_from_a_live_body() {
        json(Map.of("connectorId", restConnectorPointingAtTheHub(), "endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(200)
            .body(containsString("path: /q/health"))
            .body(containsString("target:"));
    }

    /**
     * A connector that cannot address a source at all is the operator's row to fix — 400, not the
     * 502 an upstream refusal earns, and not the 500 {@code Urls.join} used to raise.
     *
     * <p>The row is reachable: this very test writes it through {@code POST
     * /api/platform/connectors}, the flat CRUD door, which persists a config map <em>without</em>
     * running connector-type validation. The apply path could never produce one.
     */
    @Test
    void probe_refuses_a_connector_that_declares_no_base_url() {
        String cid = restConnector(Map.of("auth", Map.of("kind", "none")));

        json(Map.of("connectorId", cid, "endpointPath", "/partners"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(400)
            .body("error", containsString("base_url"));
    }

    /**
     * A secret that does not resolve is the operator's row to fix — 400 — and it must never be
     * reported as the source refusing. The auth factories build what they have and refuse only in
     * {@code probe()}, so without that call this connector would send an empty Basic password and
     * the hub would relay the upstream's 401 as a 502.
     */
    @Test
    void probe_refuses_a_credential_that_does_not_resolve() {
        String cid = restConnector(Map.of("base_url", hub(), "auth",
            Map.of("kind", "basic", "username", "demo", "password_env", "NO_SUCH_SECRET_FOR_THIS_IT")));

        json(Map.of("connectorId", cid, "endpointPath", "/q/health"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(400)
            .body("error", containsString("NO_SUCH_SECRET_FOR_THIS_IT"));
    }

    /**
     * A source nobody is listening on is the upstream's problem too, and it reached the operator as
     * the same bare 500 — with the diagnosis ("Connection refused") kept in the hub log. Port 1 is
     * never bound.
     */
    @Test
    void probe_reports_a_source_it_could_not_reach() {
        String cid = restConnector(Map.of("base_url", "http://localhost:1"));

        json(Map.of("connectorId", cid, "endpointPath", "/partners"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(502)
            .body("error", containsString("could not reach"));
    }

    /**
     * The third layer of the finding: with everything else right, an upstream refusal escaped the
     * resource as a {@link io.lumnik.hub.connector.exceptions.ConnectorException} and reached the
     * operator as {@code Internal Server Error} plus an error id — while the hub log held the exact
     * status and body. The status belongs in the answer.
     */
    @Test
    void probe_reports_the_upstream_status_instead_of_a_bare_500() {
        json(Map.of("connectorId", restConnectorPointingAtTheHub(), "endpointPath", "/q/no-such-path"))
        .when().post("/api/platform/sources/rest/probe")
        .then().statusCode(502)
            .body("error", containsString("HTTP 404"));
    }
}
