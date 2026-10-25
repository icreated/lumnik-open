package io.lumnik.hub.app;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * The delete door — {@code AppApplyResourceIT}'s twin. Dev-bypass is on in %test, so this IT is
 * about the two refusals and the audit record, the same split {@code AppApplyResourceIT}'s own
 * javadoc explains; {@code AppRegistryAuthIT} covers the role gate and the scope refusal under a
 * real token.
 */
@QuarkusTest
class AppWriteResourceIT {

    @Inject AppRegistrationStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String latestAuditChanges(String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT changes::text FROM core.audit_outbox "
                     + "WHERE entity_type = 'AppRegistration' AND operation = 'DELETE' "
                     + "AND entity_key = ? AND actor_id = 1 ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void deletesAnOrdinaryAppAndLandsInTheAuditOutbox() throws Exception {
        given().config(YamlBody.CONFIG)
            .header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .contentType("application/yaml")
            .body(manifest("salon-a-supprimer", "salon", ""))
            .when().post("/api/apps/apply").then().statusCode(200);

        given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .when().delete("/api/apps/salon-a-supprimer")
            .then().statusCode(204);

        assertThat(store.find("salon-a-supprimer")).isEmpty();
        assertThat(latestAuditChanges("salon-a-supprimer"))
            .as("who removed which scope's screen")
            .isNotNull();
    }

    @Test
    void refusesAnUnknownName() {
        given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .when().delete("/api/apps/jamais-vu-suppr-http")
            .then().statusCode(404).body("error", containsString("jamais-vu-suppr-http"));
    }

    @Test
    void refusesToDeleteTheLastDefault() {
        String current = store.findDefaultName().orElseThrow();

        given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .when().delete("/api/apps/" + current)
            .then().statusCode(400)
            .body("error", containsString("cannot remove the last default app"));

        assertThat(store.findDefaultName())
            .as("an absent ?m= must still resolve to something after the refusal")
            .contains(current);
    }
}
