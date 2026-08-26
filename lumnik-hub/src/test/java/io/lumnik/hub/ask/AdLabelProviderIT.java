package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PREFLIGHT finding: quarkus.datasource."admin".jdbc.url uses quoted notation so
 * @ConfigProperty with unquoted keys does not resolve in %test.
 * Fallback: derive the JDBC URL from adminDs metadata; use DevServices defaults
 * lumnik_app / lumnik_app_dev_password (set in %test application.properties
 * via LUMNIK_APP_DB_USERNAME / LUMNIK_APP_DB_PASSWORD defaults).
 */
@QuarkusTest
class AdLabelProviderIT {

    @Inject AdLabelProvider provider;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private static final String USER = "lumnik_app";
    private static final String PASSWORD = "lumnik_app_dev_password";

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seedAd() throws Exception {
        // Derive the JDBC URL from the live datasource connection
        try (Connection c = adminDs.getConnection()) {
            jdbcUrl = c.getMetaData().getURL();
        }

        exec("DROP TABLE IF EXISTS ad_column; DROP TABLE IF EXISTS ad_table; DROP TABLE IF EXISTS ad_element");
        exec("CREATE TABLE ad_table (ad_table_id int primary key, tablename text)");
        exec("CREATE TABLE ad_element (ad_element_id int primary key, name text)");
        exec("CREATE TABLE ad_column (ad_column_id int primary key, ad_table_id int, columnname text, "
           + "name text, ad_element_id int)");
        exec("INSERT INTO ad_table VALUES (100, 'C_Order')");
        exec("INSERT INTO ad_element VALUES (9, 'N° de chantier')");
        exec("INSERT INTO ad_column VALUES (1, 100, 'DocumentNo', 'Document No', NULL)");
        exec("INSERT INTO ad_column VALUES (2, 100, 'CustCol_47', 'raw', 9)");
    }

    @Test
    void readsAdLabelsForTable() {
        Map<String,String> labels = provider.labelsForTable(jdbcUrl, USER, PASSWORD, "c_order");
        assertThat(labels).containsEntry("documentno", "Document No");
        assertThat(labels).containsEntry("custcol_47", "N° de chantier");
    }

    @Test
    void unreachableSourceYieldsEmptyMap_noThrow() {
        Map<String,String> labels = provider.labelsForTable(
            "jdbc:postgresql://127.0.0.1:1/nope", "x", "y", "c_order");
        assertThat(labels).isEmpty();
    }

    @Test
    void unknownTableYieldsEmptyMap() {
        Map<String,String> labels = provider.labelsForTable(jdbcUrl, USER, PASSWORD, "no_such_table");
        assertThat(labels).isEmpty();
    }
}
