package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConnectorDeleteCleanupIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject DynamicTableRegistry registry;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void delete_drops_dynamic_table_registry_and_cascades() throws Exception {
        execSql("DROP TABLE IF EXISTS connector.t_deltest");

        Path csv = Files.createTempFile("deltest_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x.com\n2,b@x.com\n");

        UUID id = persistCsvConnector("deltest",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", true), "deltest");

        engine.runSync(id, new RunTrigger.Manual(1L));

        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='connector' AND table_name='t_deltest'")).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("the run must register the dynamic table against its connector_id").isGreaterThanOrEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.connector_run WHERE connector_id='" + id + "'"))
            .isGreaterThanOrEqualTo(1L);

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + id)
            .then().statusCode(204);

        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='connector' AND table_name='t_deltest'"))
            .as("physical dynamic table must be dropped").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("registry rows must be deleted").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.connector_run WHERE connector_id='" + id + "'"))
            .as("run rows must cascade").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.connector WHERE id='" + id + "'"))
            .as("connector must be deleted").isEqualTo(0L);
    }

    @Test
    void fk_cascade_removes_registry_row_when_connector_deleted_directly() throws Exception {
        UUID id = persistBareConnector("fkcascade");
        registry.register(1L, "m-fk-" + id, "connector", "t_fk_unused",
            Map.of("row_id", "text"), id);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .isEqualTo(1L);

        TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                connectors.delete(connectors.findById(id));
                utx.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("the FK ON DELETE CASCADE must remove the registry row").isEqualTo(0L);
    }

    @Test
    void purge_drops_dynamic_tables_whose_name_contains_a_hyphen() throws Exception {
        // The writer quotes identifiers when creating tables, so a name with '-' is
        // valid and exists as "t_hy-phen". purge must drop it (an allowlist would
        // wrongly reject it and break the delete).
        execSql("DROP TABLE IF EXISTS connector.\"t_hy-phen\"");
        UUID id = persistBareConnector("hyphen");
        execSql("CREATE TABLE connector.\"t_hy-phen\" (id bigint)");
        registry.register(1L, "m-hy-" + id, "connector", "t_hy-phen", Map.of("id", "bigint"), id);
        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='connector' AND table_name='t_hy-phen'")).isEqualTo(1L);

        registry.purgeForConnector(id);

        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='connector' AND table_name='t_hy-phen'"))
            .as("hyphenated dynamic table must be dropped").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("registry rows must be deleted").isEqualTo(0L);
    }

    @Test
    void purge_drops_registered_ext_tables_with_the_connector() throws Exception {
        // C4/P18 PR1 registered hybrid ext.* tables in the registry; connector delete must
        // drop them like connector.t_* — same erasure gesture, same symmetry (the one-shot
        // CSV path never registers, so it is naturally out of reach of this purge).
        execSql("CREATE SCHEMA IF NOT EXISTS ext");
        execSql("DROP TABLE IF EXISTS ext.purge_probe");
        execSql("CREATE TABLE ext.purge_probe (id bigint)");
        UUID id = persistBareConnector("extpurge");
        registry.register(1L, "hybrid:ext.purge_probe", "ext", "purge_probe", Map.of("id", "bigint"), id);

        registry.purgeForConnector(id);

        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='ext' AND table_name='purge_probe'"))
            .as("a registered ext.* table must be dropped with its connector").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .isEqualTo(0L);
    }

    @Test
    void purge_does_not_drop_tables_outside_the_dynamic_convention() throws Exception {
        // Blast-radius guard: a registry row pointing at a non-(connector.t_*) table
        // (a corrupted/foreign row) must NOT be dropped by purge.
        execSql("DROP TABLE IF EXISTS connector.guard_probe");
        execSql("CREATE TABLE connector.guard_probe (id bigint)");
        UUID id = persistBareConnector("guard");
        registry.register(1L, "m-guard-" + id, "connector", "guard_probe", Map.of("id", "bigint"), id);

        registry.purgeForConnector(id);

        assertThat(scalar("SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='connector' AND table_name='guard_probe'"))
            .as("a non-t_ table must NOT be dropped by purge (blast-radius guard)").isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("the connector's registry rows are still deleted").isEqualTo(0L);
        execSql("DROP TABLE IF EXISTS connector.guard_probe");
    }

    private UUID persistCsvConnector(String name, Map<String, Object> transport,
                                     Map<String, Object> parser, String table) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", transport);
                cfg.put("parser", parser);
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName(name + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(transport.getOrDefault("path", "").toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                endpoints.flush();
                utx.commit();
                return c.getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private UUID persistBareConnector(String name) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName(name + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file"));
                connectors.persist(c);
                connectors.flush();
                utx.commit();
                return c.getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
