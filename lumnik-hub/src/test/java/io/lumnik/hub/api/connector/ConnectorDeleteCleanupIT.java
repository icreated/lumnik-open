package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.testsupport.SharedTableFixture;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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
        UUID id = runCsv(1L, "deltest", "row_id,email\n1,a@x.com\n2,b@x.com\n");

        assertThat(tables("t_deltest")).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("the run must register the dynamic table against its connector_id").isGreaterThanOrEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.connector_run WHERE connector_id='" + id + "'"))
            .isGreaterThanOrEqualTo(1L);

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + id)
            .then().statusCode(204);

        assertThat(tables("t_deltest"))
            .as("physical dynamic table must be dropped").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + id + "'"))
            .as("registry rows must be deleted").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.connector_run WHERE connector_id='" + id + "'"))
            .as("run rows must cascade").isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM connector.connector WHERE id='" + id + "'"))
            .as("connector must be deleted").isEqualTo(0L);
    }

    @Test
    void a_delete_keeps_the_rows_another_tenant_wrote_into_the_same_table() throws Exception {
        // #1186: the table name carries no tenant, so two tenants' connectors of one name share it
        execSql("DROP TABLE IF EXISTS connector.t_shared1186");
        UUID mine = runCsv(1L, "shared1186", "row_id,email\n1,a@x.com\n2,b@x.com\n");
        UUID theirs = runCsv(2L, "shared1186", "row_id,email\n3,c@x.com\n");

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + mine)
            .then().statusCode(204);

        assertThat(scalar("SELECT count(*) FROM connector.t_shared1186 WHERE tenant_id = 2"))
            .as("the other tenant's rows survive").isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM connector.t_shared1186 WHERE tenant_id = 1"))
            .as("the deleted connector's rows go").isZero();
        assertThat(scalar("SELECT count(*) FROM connector.dynamic_table WHERE connector_id='" + theirs + "'"))
            .as("the other registration stays").isEqualTo(1L);

        given().headers(TestIdentity.tenantAndUser(2))
            .when().delete("/api/platform/connectors/" + theirs)
            .then().statusCode(204);

        assertThat(tables("t_shared1186"))
            .as("the last writer's delete drops the table").isZero();
    }

    @Test
    void a_delete_keeps_the_rows_a_sibling_connector_wrote_into_the_same_table() throws Exception {
        // #1186: one tenant, two csv connectors whose endpoints name the same table — a sharing that
        // predates #1199's refusal, registered for the second connector as V72 found it
        execSql("DROP TABLE IF EXISTS connector.t_sibling1186");
        UUID first = runCsv(1L, "sibling1186", "row_id,email\n1,a@x.com\n");
        UUID second = csvConnector(1L, "sibling1186", "row_id,email\n2,b@x.com\n3,c@x.com\n");
        execSql(SharedTableFixture.registerAsWriter(second, "t_sibling1186"));
        run(second);

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + first)
            .then().statusCode(204);

        assertThat(scalar("SELECT count(*) FROM connector.t_sibling1186"))
            .as("the sibling's two rows survive").isEqualTo(2L);
        assertThat(scalar("SELECT count(*) FROM connector.t_sibling1186 WHERE email = 'a@x.com'"))
            .as("the deleted connector's row goes").isZero();

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + second)
            .then().statusCode(204);

        assertThat(tables("t_sibling1186"))
            .as("the sibling's delete still finds the table, and drops it").isZero();
    }

    @Test
    void a_dry_run_states_the_rows_that_would_go_and_deletes_nothing() throws Exception {
        // #1218: the rows counted are the ones the delete would take — this connector's, not the
        // other tenant's sharing the table
        execSql("DROP TABLE IF EXISTS connector.t_dry1218");
        UUID mine = runCsv(1L, "dry1218", "row_id,email\n1,a@x.com\n2,b@x.com\n");
        runCsv(2L, "dry1218", "row_id,email\n3,c@x.com\n");

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + mine + "?dryRun=true")
            .then().statusCode(200)
            .body("dryRun", equalTo(true))
            .body("tables.table", contains("connector.t_dry1218"))
            .body("tables.rows", contains(2));

        assertThat(scalar("SELECT count(*) FROM connector.t_dry1218"))
            .as("a dry run deletes no row").isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM connector.connector WHERE id='" + mine + "'"))
            .as("a dry run keeps the connector").isEqualTo(1L);
    }

    @Test
    void a_dry_run_is_refused_like_the_delete_when_a_view_reads_the_table() throws Exception {
        execSql("DROP VIEW IF EXISTS connector.v_blocks1218");
        execSql("DROP TABLE IF EXISTS connector.t_blocked1218");
        UUID id = runCsv(1L, "blocked1218", "row_id,email\n1,a@x.com\n");
        execSql("CREATE VIEW connector.v_blocks1218 AS SELECT * FROM connector.t_blocked1218");
        try {
            given().headers(TestIdentity.tenantAndUser(1))
                .when().delete("/api/platform/connectors/" + id + "?dryRun=true")
                .then().statusCode(409)
                .body("error", containsString("connector.v_blocks1218"));
        } finally {
            execSql("DROP VIEW IF EXISTS connector.v_blocks1218");
        }
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
    void purge_keeps_the_rows_another_connector_upserted_into_a_shared_ext_table() throws Exception {
        execSql("CREATE SCHEMA IF NOT EXISTS ext");
        execSql("DROP TABLE IF EXISTS ext.shared_probe");
        execSql("CREATE TABLE ext.shared_probe (tenant_id bigint NOT NULL, connector_id uuid NOT NULL, v text)");
        UUID mine = persistBareConnector("extmine");
        UUID theirs = persistBareConnector("exttheirs");
        registry.register(1L, "hybrid:ext.shared_probe", "ext", "shared_probe", Map.of("v", "text"), mine);
        registry.register(2L, "hybrid:ext.shared_probe", "ext", "shared_probe", Map.of("v", "text"), theirs);
        execSql("INSERT INTO ext.shared_probe VALUES (1, '" + mine + "', 'a'), (2, '" + theirs + "', 'b')");

        registry.purgeForConnector(mine);

        assertThat(scalar("SELECT count(*) FROM ext.shared_probe WHERE connector_id = '" + theirs + "'"))
            .as("the other connector's row survives").isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM ext.shared_probe")).as("this connector's row goes").isEqualTo(1L);
        execSql("DROP TABLE IF EXISTS ext.shared_probe");
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

    /** A csv connector of {@code tenant} whose one endpoint writes {@code body} into {@code t_<table>}, run once. */
    private UUID runCsv(long tenant, String table, String body) throws Exception {
        return run(csvConnector(tenant, table, body));
    }

    private UUID csvConnector(long tenant, String table, String body) throws Exception {
        Path csv = Files.createTempFile(table + "_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, body);
        return persistCsvConnector(tenant, table, csv.toAbsolutePath().toString());
    }

    private UUID run(UUID id) {
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        return id;
    }

    private UUID persistCsvConnector(long tenant, String table, String path) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", Map.of("kind", "local", "path", path));
                cfg.put("parser", Map.of("has_header", true));
                Connector c = new Connector();
                c.setTenantId(tenant);
                c.setName(table + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(tenant);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(path);
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

    /** 1 when {@code connector.<name>} exists, 0 when it does not. */
    private long tables(String name) {
        return scalar("SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema='connector' AND table_name='" + name + "'");
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
