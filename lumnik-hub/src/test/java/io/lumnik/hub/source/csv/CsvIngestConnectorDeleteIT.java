package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "Ext tables die with the connector" (#318) must hold for the one-shot CSV ingest too.
 * The one-shot writes its rows INSIDE the transaction that creates its connector row, so
 * the registry insert (admin datasource, FK on connector) cannot see the row yet and the
 * table stayed unregistered — and the delete purge, which walks the registry, then left
 * the table orphaned. Found by the first real installer: {@code lm connector delete}
 * removed the connector, {@code ext.ad_user} stayed.
 */
@QuarkusTest
class CsvIngestConnectorDeleteIT {

    @Inject CsvIngestService service;
    @Inject DynamicTableRegistry dynamicTables;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void deleting_a_one_shot_connector_drops_its_ext_table() throws Exception {
        Path csv = Files.createTempFile("delete-probe", ".csv");
        Files.writeString(csv, "code,label\nA1,alpha\n", StandardCharsets.UTF_8);
        csv.toFile().deleteOnExit();

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "delete_probe";
        form.scope = "system";

        // Committed like a real request: the after-commit registration must actually fire.
        CsvIngestResult result = QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));
        assertEquals("ext.delete_probe", result.tableName());
        assertNotNull(regclass("ext.delete_probe"), "the ingest created the table");

        dynamicTables.purgeForConnector(result.connectorId());

        assertNull(regclass("ext.delete_probe"),
            "ext tables die with the connector — the one-shot's table must not be orphaned");
    }

    private Object regclass(String table) throws Exception {
        try (var c = adminDs.getConnection();
             var ps = c.prepareStatement("SELECT to_regclass(?)")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }
}
