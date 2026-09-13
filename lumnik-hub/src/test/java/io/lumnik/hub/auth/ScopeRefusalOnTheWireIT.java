package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.testsupport.LogProbe;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The métier-scope refusal, measured ON THE WIRE, by a caller who is actually BOUND.
 *
 * <p>Every scope refusal in the hub was pinned at the unit level — the door returns or throws a 403
 * carrying {@code {"error":"scope not granted"}} — and nothing pinned the conversion to an HTTP
 * response, for any door (#514). Two shapes ship side by side: four doors {@code return
 * ApiErrors.scopeNotGranted()}, while {@link ApplyScopeBoundary} and {@code EntityApplyResource}
 * {@code throw new WebApplicationException(…)} with that same response attached. The thrown one
 * rests on there being no {@code ExceptionMapper} for {@code WebApplicationException} in this app —
 * a framework fact, true or false for all five doors at once, and until now asserted by nobody.
 *
 * <p><b>Driving a bound caller needs no new machinery.</b> {@link NoBypassOidcProfile} already mints
 * a real, signed bearer JWT with an arbitrary {@code groups} claim — written because
 * {@code @TestSecurity} cannot populate the injected {@code JsonWebToken} on this OIDC-only app.
 * {@code TenantContextFilter.bindRoles()} copies {@code groups} into {@code CurrentRolesHolder}, and
 * {@code ScopeGrants} reads exactly that holder. So a {@code scope:magasin} in the minted
 * {@code groups} is the whole harness; #514 listed a test-only header on the dev-bypass path and a
 * Keycloak devservice as the options, and neither is needed.
 *
 * <p>The door is {@code POST /api/platform/connectors/apply}, because it carries BOTH shapes — a
 * returned refusal for the scope the manifest declares, a thrown one for the scope already stored
 * under that name. Both fall before validation, so nothing external has to exist for either to be
 * reached, and its scope is a LIST, the shape {@code allowsAll} is subtle about.
 *
 * <p>One READ door joins them, and only one (#518). Every apply door declares JSON alone;
 * {@code SavedViewsResource.get} declares JSON <em>and</em> YAML at the class level, so its
 * refusal is the only one in the tree that is NEGOTIATED rather than fixed.
 *
 * <p><b>#518 predicted a hole here and there is none</b> — which is worth more than the fix would
 * have been; {@link ApiErrors#scopeNotGranted()} carries what the mutation measured and why its
 * {@code .type()} call still earns its place. What this last test pins is therefore the scope
 * boundary on a READ door, off the socket, with the dress asserted as the thing that must not
 * change — not a defect that was there.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ScopeRefusalOnTheWireIT {

    private static String unscoped() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator"), null);
    }

    private static String boundToMagasin() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:magasin"), null);
    }

    private static final String MANIFEST = """
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: %s
          connector_type: csv-file
          scopes: [%s]
        spec:
          transport: { kind: local, path: /imports/*.csv }
          parser: { has_header: true }
          after_process: none
          target: {}
        """;

    private static Response apply(String token, String name, String scope) {
        return given().config(YamlBody.CONFIG)
            .header("Authorization", "Bearer " + token)
            .contentType("application/yaml")
            .body(MANIFEST.formatted(name, scope))
        .when().post("/api/platform/connectors/apply");
    }

    private static String name(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /**
     * A saved view living in {@code clients}, written straight to the store.
     *
     * <p>Through {@code /api/views/apply} it would need a schema card, a real table and a resolving
     * manifest — setup that belongs to the apply door's own tests. What this file is about is what
     * comes back off the socket when a bound reader asks for someone else's view, and that answer
     * is decided before the yaml is ever read.
     */
    private String savedViewInClients() {
        String name = name("wire-yaml");
        String sql = "INSERT INTO connector.saved_view (name, scope, title, yaml_text) VALUES (?,?,?,?)";
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, "clients");
            ps.setString(3, "Clients ACME");
            ps.setString(4, "apiVersion: apps.lumnik.io/v1\nkind: View\n");
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seeding the foreign-scope view failed", e);
        }
        return name;
    }

    /**
     * The one refusal, byte for byte off the socket — and both shapes come through here, so a caller
     * cannot tell a declared-scope refusal from a stored-scope one. That is the point: the difference
     * is which métier owns the name, the single bit the refusal withholds.
     *
     * <p>The bytes are written out rather than read from {@link ScopeGrants#REFUSAL}, deliberately:
     * {@code lm}'s client, the OTK oracle and the PWA hardcode this literal, so a hub-side test that
     * read the constant would follow a rename of its value into green while every consumer broke.
     * {@code SearchScopeBindingUnitTest.theRefusalWireContractIsFrozen} makes the same freeze one
     * layer up, through a local {@code ObjectMapper}; this one reads the response that actually left
     * the server.
     */
    private static void assertRefused(Response refusal) {
        String body = refusal.then().statusCode(403).contentType(ContentType.JSON)
            .extract().asString();
        assertThat(body).isEqualTo("{\"error\":\"scope not granted\"}");
    }

    /**
     * The control, and it has to come first: a caller with no {@code scope:*} role at all is
     * tenant-wide, so it publishes into {@code clients} without holding anything. Without this the
     * refusals below could all be a broken harness — a token that never authenticates refuses just
     * as convincingly as a boundary that works.
     */
    @Test
    void unscopedCaller_publishesIntoAnyScope() {
        apply(unscoped(), name("wire-unscoped"), "clients")
            .then().statusCode(200);
    }

    /** A bound caller publishing into its own métier is not refused — the boundary is not a wall. */
    @Test
    void boundCaller_publishesIntoTheScopeItHolds() {
        apply(boundToMagasin(), name("wire-own"), "magasin")
            .then().statusCode(200);
    }

    /** The RETURNED refusal: the caller declares a métier it does not hold. */
    @Test
    void boundCaller_declaringAScopeItDoesNotHold_isRefused() {
        assertRefused(apply(boundToMagasin(), name("wire-declared"), "clients"));
    }

    /**
     * A refusal leaves one WARN, written where the refusal is built. Until now the role gate logged
     * its {@code Access denied} while the scope gate refused in silence — a SIEM subscribed to the
     * one line never saw the other family. The line names the caller's grants, never the token and
     * never a row; asserting on the granted set is what proves it is this refusal and not a stray.
     */
    @Test
    void aScopeRefusal_leavesOneWarnNamingTheGrants() {
        List<String> warnings = LogProbe.warnings(ApiErrors.class,
            () -> assertRefused(apply(boundToMagasin(), name("wire-logged"), "clients")));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
            .startsWith("Access denied: scope not granted")
            .contains("granted=[magasin]")
            .doesNotContain("Bearer");
    }

    /**
     * The THROWN refusal ({@link ApplyScopeBoundary#refuseUnlessCallerHolds}), and the reason this
     * file exists: the take-over that class's javadoc describes, played over HTTP. The incoming check
     * passes — every scope declared is held — and only the stored half refuses, by THROWING.
     *
     * <p>Asserting the status alone would not have measured that: a swallowed throw and a working one
     * both stop the overwrite. What is new is the body, arriving intact from a thrown response.
     */
    @Test
    void boundCaller_takingOverAManifestOfAScopeItDoesNotHold_isRefused() {
        String contested = name("wire-takeover");
        apply(unscoped(), contested, "clients").then().statusCode(200);

        assertRefused(apply(boundToMagasin(), contested, "magasin"));
    }

    /**
     * The refusal keeps its JSON dress on a door that also speaks YAML, even when the caller asks
     * for YAML by name — the one per-door axis the apply doors cannot exercise (#518).
     *
     * <p>{@code Accept: application/yaml} is what a reader of this door legitimately sends: a
     * granted view comes back AS yaml, which is the whole point of the class-level
     * {@code @Produces}. The refusal does not follow it there — see the class javadoc for which
     * half of that sentence is load-bearing and which half the tree gets for free today.
     */
    @Test
    void aRefusalOnAJsonAndYamlDoor_keepsItsJsonDressEvenWhenYamlIsAsked() {
        String foreign = savedViewInClients();

        Response refusal = given()
            .header("Authorization", "Bearer "
                + NoBypassOidcProfile.token(Set.of("lm_user", "scope:magasin"), null))
            .accept("application/yaml")
        .when().get("/api/views/" + foreign);

        assertRefused(refusal);
    }
}
