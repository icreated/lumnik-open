package io.lumnik.hub.source.csv;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A one-shot's dormant endpoint (enabled=false, org_id=0) must be visible to its own doors:
 * {@code GET .../endpoints} used to filter {@code enabled = true} and returned {@code []}, so
 * {@code lm endpoint purge} could never resolve the name; and {@code .../endpoints/{eid}/purge}
 * used {@code WHERE _endpoint_id}, a column the ext.* tables (which have {@code endpoint_id}) do not
 * have, so it 500'd. Both proven here, end to end.
 */
@QuarkusTest
class OneShotEndpointVisibilityIT {

    @Inject CsvIngestService service;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void a_one_shots_dormant_endpoint_is_listed_and_purgeable() throws Exception {
        String name = "epvis_probe";
        String table = "ext." + name;

        CsvIngestResult r = ingest(name, "code,amount\nA1,10.5\nB2,20\n");
        assertThat(rows(table)).as("two rows ingested").isEqualTo(2L);

        // Defect 1: the dormant endpoint must appear in its connector's endpoint list.
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/connectors/" + r.connectorId() + "/endpoints")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].name", equalTo(name))
            .body("[0].enabled", equalTo(false));

        // Defect 2: purge must delete the endpoint's rows, not 500 on a non-existent column.
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + r.connectorId() + "/endpoints/" + r.endpointId() + "/purge")
            .then().statusCode(200)
            .body("deleted", equalTo(2));

        assertThat(rows(table)).as("purge cleared the endpoint's rows").isZero();
    }

    private CsvIngestResult ingest(String name, String csv) throws Exception {
        Path f = Files.createTempFile("epvis_", ".csv");
        Files.writeString(f, csv, StandardCharsets.UTF_8);
        f.toFile().deleteOnExit();
        CsvIngestForm form = new CsvIngestForm();
        form.file = f.toFile();
        form.name = name;
        form.scope = "epvis";
        return QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));
    }

    private long rows(String table) {
        return Jdbc.queryOne(adminDs, "epvis.count", "SELECT count(*) FROM " + table,
            b -> { }, rs -> rs.getLong(1)).orElse(-1L);
    }
}
