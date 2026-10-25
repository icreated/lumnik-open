package io.lumnik.hub.app;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.equalTo;

/**
 * The curated door. Dev-bypass is on in %test, so this IT is about VALIDATION, the two default
 * invariants and the audit record — an auth assertion written here would pass whatever the gate
 * did. Of the four authorization questions this door answers, AppRegistryAuthIT proves two under
 * a real token: the lm_integrator gate and the refusal on the demoted default. The other two — the
 * refusal on the declared scope and on the overwritten row — are covered indirectly:
 * ScopeBoundaryInventoryTest sweeps the source to prove every *ApplyResource calls
 * ApplyScopeBoundary.refuseUnless…, and ScopeRefusalOnTheWireIT measures the throw-to-response
 * conversion once for all apply doors at a time, since that conversion is a framework fact, true
 * or false for every door together.
 */
@QuarkusTest
class AppApplyResourceIT {

    @Inject AppRegistrationStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    // X-User-ID as well as X-Tenant-ID: under dev-bypass TenantContextFilter reads both headers,
    // and AuditRecorder writes actor_id from the second one — without it the audit assertions
    // below would be matching on a NULL actor, i.e. on nothing.
    private static RequestSpecification post() {
        return given().config(YamlBody.CONFIG)
            .header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .contentType("application/yaml");
    }

    /** The newest audit_outbox row for this app name, or null — {@code changes} as raw jsonb text. */
    private String latestAuditChanges(String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT changes::text FROM core.audit_outbox "
                     + "WHERE entity_type = 'AppRegistration' AND operation = 'APPLY' "
                     + "AND entity_key = ? AND actor_id = 1 ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void appliesAManifestAndStoresItsResolvedIdentity() {
        post().body(manifest("salon-printemps", "salon", "  theme:\n    accentColor: \"#4fd1a5\"\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(200)
            .body("name", equalTo("salon-printemps"))
            .body("scope", equalTo("salon"))
            .body("default", equalTo(false));

        AppRegistrationStore.Row row = store.find("salon-printemps").orElseThrow();
        assertThat(row.title()).isEqualTo("salon-printemps");
        assertThat(row.icon()).isEqualTo("event");
        assertThat(row.accentColor()).isEqualTo("#4fd1a5");
        assertThat(row.backgroundColor()).as("the parser fills the theme in").isEqualTo("#14121f");
    }

    // --- spec.tabs (#1030): nullable, stored as written, portable between editions

    @Test
    void tabsRoundTripAndAreServedByTheRegistry() {
        post().body(manifest("tabs-round-trip", "salon", "  tabs: [fiches, inbox]\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(200)
            .body("tabs", contains("fiches", "inbox"))
            .body("$", not(hasKey("warnings")));

        assertThat(store.find("tabs-round-trip").orElseThrow().tabs()).containsExactly("fiches", "inbox");
        given().header("X-Tenant-ID", "1").when().get("/api/apps/tabs-round-trip")
            .then().statusCode(200).body("tabs", contains("fiches", "inbox"));
    }

    @Test
    void anAppWithoutTabsInheritsAndReApplyingWithoutThemClearsThem() {
        post().body(manifest("tabs-inherit", "salon", "  tabs: [fiches]\n"))
            .when().post("/api/apps/apply").then().statusCode(200);
        assertThat(store.find("tabs-inherit").orElseThrow().tabs()).containsExactly("fiches");

        post().body(manifest("tabs-inherit", "salon", ""))
            .when().post("/api/apps/apply")
            .then().statusCode(200).body("$", not(hasKey("tabs")));
        assertThat(store.find("tabs-inherit").orElseThrow().tabs()).isNull();
        given().header("X-Tenant-ID", "1").when().get("/api/apps/tabs-inherit")
            .then().statusCode(200).body("$", hasKey("tabs")).body("tabs", nullValue());
        given().header("X-Tenant-ID", "1").when().get("/api/apps/clients")
            .then().statusCode(200).body("$", hasKey("tabs")).body("tabs", nullValue());
    }

    @Test
    void aTabNoModuleOffersIsKeptVerbatimAndReportedAsAWarning() {
        post().body(manifest("tabs-portable", "salon", "  tabs: [fiches, holodeck]\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(200)
            .body("tabs", contains("fiches", "holodeck"))
            .body("warnings", hasSize(1))
            .body("warnings[0].path", equalTo("spec.tabs[1]"))
            .body("warnings[0].severity", equalTo("WARNING"))
            .body("warnings[0].message", containsString("holodeck"));
        assertThat(store.find("tabs-portable").orElseThrow().tabs()).containsExactly("fiches", "holodeck");
    }

    @Test
    void anEmptyTabListIsRefusedByTheDoor() {
        post().body(manifest("tabs-empty", "salon", "  tabs: []\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(400).body("error", containsString("omit the key"));
        assertThat(store.find("tabs-empty")).isEmpty();
    }

    @Test
    void theDatabaseRefusesAnEmptyArrayOrANullElementEvenIfADoorWereBypassed() throws Exception {
        post().body(manifest("tabs-check", "salon", "")).when().post("/api/apps/apply").then().statusCode(200);
        for (String bad : new String[] {"'{}'", "ARRAY['fiches', NULL]::text[]"}) {
            try (Connection c = adminDs.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE connector.app_registration SET tabs = " + bad + " WHERE name = 'tabs-check'")) {
                SQLException e = assertThrows(SQLException.class, ps::executeUpdate);
                assertThat(e.getSQLState()).as("check_violation for %s", bad).isEqualTo("23514");
            }
        }
    }

    @Test
    void refusesANameThatCouldNotBeFetchedBack() {
        post().body(manifest("Salon Printemps!", "salon", ""))
            .when().post("/api/apps/apply")
            .then().statusCode(400).body("error", containsString("URL-safe slug"));
    }

    @Test
    void refusesTheSiblingEndpointLiteral() {
        post().body(manifest("apply", "salon", ""))
            .when().post("/api/apps/apply")
            .then().statusCode(400).body("error", containsString("URL-safe slug"));
    }

    @Test
    void refusesAnUnknownKey() {
        post().body(manifest("salon-ete", "salon", "  titel: oops\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(400)
            .body("error", containsString("unknown key(s)"))
            .body("error", containsString("spec.titel"))
            .body("error", containsString("kind:App honors"));
    }

    @Test
    void refusesAnUnknownKeyWithTheExactSharedRefusalBody() {
        post().body(manifest("salon-hiver", "salon", "  titel: oops\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(400)
            .body("error", equalTo("unknown key(s) in the manifest: spec.titel — kind:App honors "
                + AppManifestParser.honoredSurface()));
    }

    @Test
    void refusesAnInvalidSlugWithTheExactSharedRefusalBody() {
        post().body(manifest("Salon Printemps!", "salon", ""))
            .when().post("/api/apps/apply")
            .then().statusCode(400)
            .body("error", equalTo("metadata.name must be a URL-safe slug ([a-z0-9_-], starting "
                + "with a letter or digit) and not one of " + AppApplyResource.RESERVED_NAMES));
    }

    @Test
    void refusesAnIconOutsideTheCuratedSet() {
        post().body(manifest("salon-auto", "salon", "").replace("icon: event", "icon: unicorn"))
            .when().post("/api/apps/apply")
            .then().statusCode(400).body("error", containsString("spec.icon must be one of"));
    }

    @Test
    void refusesAMalformedColour() {
        post().body(manifest("salon-hiver", "salon", "  theme:\n    accentColor: \"green\"\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(400).body("error", containsString("6-digit hex colour"));
    }

    @Test
    void refusesAnAccentTheChatBubbleWouldSwallow() {
        post().body(manifest("salon-nuit", "salon", "  theme:\n    accentColor: \"#101010\"\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(400)
            .body("error", containsString("does not have enough contrast"));
    }

    @Test
    void promotingANewDefaultDemotesThePreviousOneInOneCall() throws Exception {
        // Every @QuarkusTest in this module shares one database and JUnit guarantees no order
        // between classes, so this test RESTORES what it moves — in a finally, not on the happy
        // path. AppRegistrationSeedIT and AppRegistryResourceIT both assert that clients is the
        // default; a failure here must not turn into a red over there tomorrow.
        String previous = store.findDefaultName().orElseThrow();
        try {
            post().body(manifest("crise", "crise", "  default: true\n"))
                .when().post("/api/apps/apply")
                .then().statusCode(200)
                .body("default", equalTo(true))
                .body("demotedDefault", equalTo(previous));

            assertThat(store.findDefaultName()).contains("crise");
            assertThat(store.find(previous).orElseThrow().isDefault()).isFalse();

            // Audit P4: the upsert goes through raw JDBC, outside every Hibernate listener, so
            // this row is the ONLY record of who moved the deployment's landing app — and of the
            // app that lost the flag without being addressed. demoted_default is the whole reason
            // upsert threads the demoted name back out; the response body is not a record.
            assertThat(latestAuditChanges("crise"))
                .as("the apply must say what it cost someone else")
                .contains("\"demoted_default\": \"" + previous + "\"")
                .contains("\"scope\": \"crise\"")
                .contains("manifest_sha256");
        } finally {
            AppRegistrationStore.Row row = store.find(previous).orElseThrow();
            // The restore carries the row's ACTUAL theme, not the parser's defaults. Those two
            // happen to be equal today (V48 seeds --accent/--bg verbatim) — and AppRegistrationSeedIT
            // asserts the seeded values, so a themeless restore would quietly rewrite the row it is
            // restoring and turn any future move in either default into a red over there.
            post().body("""
                kind: App
                metadata:
                  name: %s
                spec:
                  title: %s
                  scope: %s
                  icon: %s
                  default: true
                  theme:
                    accentColor: "%s"
                    backgroundColor: "%s"
                """.formatted(row.name(), row.title(), row.scope(), row.icon(),
                    row.accentColor(), row.backgroundColor()))
                .when().post("/api/apps/apply").then().statusCode(200);
        }
        assertThat(store.findDefaultName()).contains(previous);
    }

    @Test
    void anOrdinaryApplyLandsInTheAuditOutbox() throws Exception {
        post().body(manifest("salon-audit", "salon", ""))
            .when().post("/api/apps/apply").then().statusCode(200);

        assertThat(latestAuditChanges("salon-audit"))
            .as("who changed which app's identity, and which bytes they applied")
            .isNotNull()
            .contains("\"scope\": \"salon\"")
            .contains("manifest_sha256")
            .doesNotContain("demoted_default");
    }

    @Test
    void refusesToRemoveTheLastDefaultWithoutAReplacement() {
        // Reads the CURRENT default rather than assuming clients, for the same order-independence
        // reason as above — and because the refusal is about "the last one", not about that name.
        AppRegistrationStore.Row current = store.find(store.findDefaultName().orElseThrow()).orElseThrow();

        post().body("""
            kind: App
            metadata:
              name: %s
            spec:
              title: %s
              scope: %s
              icon: %s
              default: false
            """.formatted(current.name(), current.title(), current.scope(), current.icon()))
            .when().post("/api/apps/apply")
            .then().statusCode(400)
            .body("error", containsString("cannot remove the last default app"));

        assertThat(store.findDefaultName())
            .as("an absent ?m= must still resolve to something after the refusal")
            .contains(current.name());
    }
}
