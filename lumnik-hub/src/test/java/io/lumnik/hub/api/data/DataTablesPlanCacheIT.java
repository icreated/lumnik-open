package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;

/**
 * #1599: the table browser's page is a {@code SELECT *} read in a transaction on the default pool. Once pgjdbc had
 * prepared it server-side (after five runs on a connection), any DDL that changed the table's row type — a widening,
 * a column added mid-run — made that connection answer "cached plan must not change result type": one 500 each.
 */
@QuarkusTest
class DataTablesPlanCacheIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final long TENANT = 1_599_001L;

    private void sql(String... statements) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private void page(String mapping) {
        given().headers(TestIdentity.tenantAndUser(TENANT))
            .get("/api/platform/data/tables/" + mapping + "/rows")
            .then().statusCode(200)
            .body("code", Matchers.contains("A-1", "A-2"));
    }

    @Test
    void a_page_read_before_the_table_changes_shape_is_read_after_it() throws Exception {
        String n = String.valueOf(System.nanoTime());
        String table = "t_tables_plan_" + n, mapping = "m-plan-" + n;
        sql("CREATE TABLE connector." + table + " (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, code text)",
            "GRANT SELECT ON connector." + table + " TO lumnik_app",
            "INSERT INTO connector." + table + " (tenant_id, code) VALUES (" + TENANT + ", 'A-1'), (" + TENANT + ", 'A-2')",
            "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                + "VALUES (" + TENANT + ", '" + mapping + "', 'connector', '" + table + "', '{}')");
        for (int i = 0; i < 20; i++) page(mapping);   // past pgjdbc's prepareThreshold on the connections they take
        sql("ALTER TABLE connector." + table + " ADD COLUMN extra text");   // as a column added mid-run does
        page(mapping);
    }
}
