package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purge runs on the default datasource, which is {@code lumnik_app} in production. Only this
 * profile connects as that non-owner role; the plain test container connects as an owner that sees
 * every table. {@code information_schema} lists only what the current role holds a privilege on, so
 * a table {@code lumnik_app} cannot read looked absent, and purge answered a false
 * {@code 200 deleted:0} while the rows stayed (#1181 review). The catalog does not filter by
 * privilege: the table is found, and the DELETE itself refuses.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EndpointPurgeRlsIT {

    private static final long TENANT = 1L;

    @Inject AgroalDataSource appDs;                        // default = lumnik_app
    @Inject @DataSource("admin") AgroalDataSource adminDs; // lumnik_admin (owner)

    @Test
    void a_target_the_app_role_cannot_read_is_never_reported_as_empty() {
        assertThat(scalar(appDs, "SELECT count(*) WHERE current_user = 'lumnik_app'"))
            .as("the door runs as the prod role").isEqualTo(1L);

        UUID cid = UUID.randomUUID();
        UUID eid = UUID.randomUUID();
        String table = "hybrid_noprivs_" + System.nanoTime();
        exec("CREATE TABLE connector.\"" + table + "\" (id bigint, endpoint_id uuid)");
        try {
            exec("REVOKE ALL ON connector.\"" + table + "\" FROM lumnik_app, PUBLIC");
            exec("INSERT INTO connector.\"" + table + "\" VALUES (1, '" + eid + "')");
            exec("INSERT INTO connector.connector (id, tenant_id, name, type_id) VALUES ('"
                + cid + "', " + TENANT + ", 'purge-noprivs-" + cid + "', 'rest-generic')");
            exec("INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, name, path, config) VALUES ('"
                + eid + "', '" + cid + "', " + TENANT + ", 'ep', '/', "
                + "'{\"target\":{\"table\":\"connector." + table + "\"}}'::jsonb)");

            String purge = "/api/platform/connectors/" + cid + "/endpoints/" + eid + "/purge";
            for (String url : new String[]{purge + "?dryRun=true", purge}) {
                int status = given().headers(TestIdentity.tenantAndUser(TENANT))
                    .when().post(url)
                    .then().extract().statusCode();
                assertThat(status).as("%s must not claim success over rows it cannot see", url).isNotEqualTo(200);
            }
            assertThat(scalar(adminDs, "SELECT count(*) FROM connector.\"" + table + "\"")).isEqualTo(1L);
        } finally {
            exec("DELETE FROM connector.connector WHERE id = '" + cid + "'");
            exec("DROP TABLE IF EXISTS connector.\"" + table + "\"");
        }
    }

    private void exec(String sql) {
        execSql(adminDs, sql);
    }
}
