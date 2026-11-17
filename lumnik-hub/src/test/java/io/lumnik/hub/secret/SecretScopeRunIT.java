package io.lumnik.hub.secret;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.testsupport.TestSecrets;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * #2015: a connector reads a secret only when it carries every scope the secret does. A scope-bound
 * integrator may write a connector in its own scopes; naming a secret of the tenant's in it no longer sends that
 * secret to the host the connector names. Checked where the secret is read, on the run, so a name the manifest
 * never spells out (a transport's default) is held to the same rule.
 */
@QuarkusTest
class SecretScopeRunIT {

    private static final String PATH = "/v1/scoped";

    @Inject TestSecrets testSecrets;
    @Inject ConnectorEngine engine;
    @Inject EntityManager em;

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[{\"id\":\"a\"}]")));
    }

    /**
     * The rule, case by case: the connector carries every scope of the secret, as a bound caller holds every tag of a
     * connector it reads. So a secret scoped like a connector serves only the connectors its holders may write: one
     * shared scope is not enough. A scopeless connector is the tenant's, written by an unbound caller: every secret of
     * the tenant is its. A scopeless secret is the tenant's too, and serves no connector with a scope. And a scopeless
     * secret serving a scopeless connector is what every deployment has before its first `lm secret scope`: unchanged.
     */
    @ParameterizedTest(name = "secret {0}, connector {1}: served = {2}")
    @MethodSource("cases")
    void a_connector_reads_a_secret_only_when_it_carries_all_the_secrets_scopes(List<String> secretScopes,
                                                                          List<String> connectorTags, boolean served) {
        String name = secret(secretScopes);

        RunStatus s = run(connectorTags, name);

        if (served) {
            assertThat(s).isInstanceOf(RunStatus.Completed.class);
            verify(getRequestedFor(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo(basic("pw-" + name))));
        } else {
            assertThat(s).isInstanceOf(RunStatus.Failed.class);
            assertThat(((RunStatus.Failed) s).message())
                .contains(name).contains("does not serve scope").contains("lm secret scope " + name);
            assertThat(wm.getAllServeEvents()).as("the password never left the hub").isEmpty();
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of(List.of(), List.of("ventes"), false),
            Arguments.of(List.of("ventes", "achats"), List.of("ventes"), false),
            Arguments.of(List.of("achats"), List.of("ventes"), false),
            Arguments.of(List.of("ventes"), List.of("ventes"), true),
            Arguments.of(List.of("ventes"), List.of("ventes", "achats"), true),
            Arguments.of(List.of("achats", "ventes"), List.of("ventes", "achats"), true),
            Arguments.of(List.of("ventes"), List.of(), true),
            Arguments.of(List.of(), List.of(), true));
    }

    /** A fresh name per test, so each one reads the secret it set. */
    private String secret(List<String> scopes) {
        String name = "SCOPED_PW_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, name, "pw-" + name, scopes);
        return name;
    }

    private static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("u:" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The two doors that send a connector's request outside a run read its secrets under the same rule. */
    @Test
    void the_preflight_and_the_probe_refuse_it_too() {
        Seeded c = connector(List.of("ventes"), secret(List.of()));

        RestAssured.given().header("X-Tenant-Id", "1")
            .post("/api/platform/connectors/" + c.connector() + "/endpoints/" + c.endpoint() + "/test")
            .then().statusCode(200)
            .body("ok", is(false)).body("detail", containsString("does not serve scope ventes"));
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"connectorId\":\"" + c.connector() + "\",\"endpointPath\":\"" + PATH + "\"}")
            .post("/api/platform/sources/rest/probe")
            .then().statusCode(400).body("error", containsString("does not serve scope ventes"));

        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    private RunStatus run(List<String> tags, String secretName) {
        UUID id = connector(tags, secretName).connector();
        return TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
    }

    private record Seeded(UUID connector, UUID endpoint) {}

    /** A connector and one endpoint for tenant 1, through SQL: what apply would store, without its preflight. */
    private Seeded connector(List<String> tags, String secretName) {
        Seeded c = new Seeded(UUID.randomUUID(), UUID.randomUUID());
        String table = "ext.scoped_" + Math.abs(System.nanoTime());
        TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                    "INSERT INTO connector.connector (id, tenant_id, org_id, name, type_id, config, tags)"
                    + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb), :tags)")
                .setParameter("id", c.connector())
                .setParameter("name", "scoped-" + c.connector())
                .setParameter("cfg", "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                    + "\",\"auth\":{\"kind\":\"basic\",\"username\":\"u\",\"password_env\":\"" + secretName + "\"},"
                    + "\"default_rate_limit\":\"100/s\"}")
                .setParameter("tags", tags.toArray(String[]::new))
                .executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, org_id, name, path, config)"
                    + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                .setParameter("id", c.endpoint())
                .setParameter("connId", c.connector())
                .setParameter("name", "items-" + c.connector())
                .setParameter("path", PATH)
                .setParameter("cfg", "{\"pagination\":{\"kind\":\"none\",\"response_path\":\"$\"},"
                    + "\"incremental\":{\"mode\":\"full\"},"
                    + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\"}}")
                .executeUpdate();
        }));
        return c;
    }
}
