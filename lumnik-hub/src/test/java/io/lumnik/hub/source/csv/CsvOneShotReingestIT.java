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

/**
 * Deleting a one-shot then re-ingesting under the SAME name must succeed. The audit found it 500s
 * with {@code relation ext.<name> does not exist}: the delete drops the ext table, but
 * {@link io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter}'s in-memory schema cache
 * survives the DROP, so the second ingest skips {@code CREATE TABLE} and inserts into a table that
 * is gone. The cache must be invalidated when the table is dropped.
 */
@QuarkusTest
class CsvOneShotReingestIT {

    @Inject CsvIngestService service;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void reingesting_a_deleted_one_shot_under_the_same_name_succeeds() throws Exception {
        String name = "reingest_probe";
        String table = "ext." + name;

        CsvIngestResult first = ingest(name, "code,amount\nA1,10.5\n");
        assertThat(scalar("SELECT count(*) FROM " + table)).as("first ingest row").isEqualTo(1L);

        // The real take-down: lm connector delete → drops the ext table and removes the connector.
        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + first.connectorId())
            .then().statusCode(204);
        assertThat(scalar("SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema='ext' AND table_name='" + name + "'"))
            .as("delete drops the ext table").isZero();

        // Re-ingest the same name: must recreate the table and succeed, not 500 on a stale cache.
        CsvIngestResult second = ingest(name, "code,amount\nB2,20.0\n");
        assertThat(second.connectorId()).as("second ingest produced a connector").isNotNull();
        assertThat(scalar("SELECT count(*) FROM " + table)).as("second ingest row").isEqualTo(1L);
    }

    private CsvIngestResult ingest(String name, String csv) throws Exception {
        Path f = Files.createTempFile("reingest_", ".csv");
        Files.writeString(f, csv, StandardCharsets.UTF_8);
        f.toFile().deleteOnExit();
        CsvIngestForm form = new CsvIngestForm();
        form.file = f.toFile();
        form.name = name;
        form.scope = "reingest";
        return QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));
    }

    private long scalar(String sql) {
        return Jdbc.queryOne(adminDs, "reingest", sql, b -> { }, rs -> rs.getLong(1)).orElse(-1L);
    }
}
