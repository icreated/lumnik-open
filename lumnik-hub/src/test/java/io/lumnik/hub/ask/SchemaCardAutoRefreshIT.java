package io.lumnik.hub.ask;

import io.lumnik.testkit.auth.TestIdentity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.testsupport.SharedTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaCardAutoRefreshIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject SchemaCardStore cardStore;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private String cardUpdatedAt(long tenant, String scope) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT updated_at FROM connector.schema_card WHERE tenant_id=" + tenant
                 + " AND scope='" + scope + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean cardHasTable(long tenant, String scope, String table) {
        return TenantContext.callAsSystem(() ->
            cardStore.load(tenant, scope)
                .map(card -> card.tables().stream().anyMatch(t -> t.name().contains(table)))
                .orElse(false));
    }

    @Test
    void card_refreshes_after_schema_change_and_not_after_unchanged_run() throws Exception {
        Path csv = Files.createTempFile("cidx_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x\n2,b@x\n", StandardCharsets.UTF_8);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L);
                c.setName("cidx-card-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setTags(List.of("cidx"));
                c.setConfig(Map.of("kind", "csv-file",
                    "transport", Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath(csv.toAbsolutePath().toString()); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "cidx_card"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus s = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);

        // (a) The card for scope "cidx" lists t_cidx_card WITHOUT manual rediscover (async observer → poll).
        boolean present = false;
        for (int i = 0; i < 30; i++) {
            if (cardHasTable(1L, "cidx", "cidx_card")) { present = true; break; }
            Thread.sleep(1000);
        }
        assertThat(present).as("SchemaCard auto-refreshed after schema-changing run").isTrue();

        // (b) Unchanged second run must NOT rebuild the card (updated_at stable).
        // We sleep > CLOCK_SLACK (2 s) BEFORE the second run so its look-back window
        // (runStartedAt - 2 s) cannot reach the first run's table creation. Back-to-back runs
        // are a test artifact; in production runs are minutes/hours apart.
        String before = cardUpdatedAt(1L, "cidx");
        Thread.sleep(3000);   // > CLOCK_SLACK (2 s) — push the look-back window past the first run
        engine.runSync(id, new RunTrigger.Manual(1L));
        Thread.sleep(4000);   // give the (absent) refresh a chance to (not) fire
        String after = cardUpdatedAt(1L, "cidx");
        assertThat(after).as("unchanged run must not rebuild the card").isEqualTo(before);
    }

    @Test
    void a_second_writer_of_another_scope_takes_the_table_out_of_the_first_writers_card() throws Exception {
        // #1186: the table now holds the second writer's rows too — the first card must drop it. A
        // sharing that predates #1199's refusal: the second writer is registered before its run.
        String table = "cidx_shared_" + System.nanoTime();
        UUID first = csvConnector("cidxa", table, "row_id,email\n1,a@x\n");
        assertThat(engine.runSync(first, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        awaitCard("cidxa", table, true);

        UUID second = csvConnector("cidxb", table, "row_id,email\n2,b@x\n");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(SharedTableFixture.registerAsWriter(second, "t_" + table));
        }
        assertThat(engine.runSync(second, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        awaitCard("cidxa", table, false);

        // and its delete gives the table back to the first writer's card
        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + second)
            .then().statusCode(204);
        awaitCard("cidxa", table, true);
    }

    @Test
    void deleting_the_last_connector_of_a_scope_takes_the_card_and_the_watermark_with_its_table() throws Exception {
        // #1188: the delete used to leave the card listing a dropped table, and the chat wrote SQL
        // against it; a scope with no table left keeps no card at all.
        String table = "cidx_gone_" + System.nanoTime();
        UUID id = csvConnector("cidxgone", table, "row_id,email\n1,a@x\n");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        awaitCard("cidxgone", table, true);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO core.search_projection_mark_watermark (tenant_id, table_name, watermark) "
                + "VALUES (1, 'connector.t_" + table + "', now()), (2, 'connector.t_" + table + "', now())");
        }

        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + id)
            .then().statusCode(204);

        assertThat(TenantContext.callAsSystem(() -> cardStore.load(1L, "cidxgone")))
            .as("the scope has no table left: its card goes").isEmpty();
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/rag/schema?scope=cidxgone")
            .then().statusCode(404);
        assertThat(watermarkTenants("connector.t_" + table))
            .as("this tenant's watermark goes with the table; another tenant's row is not ours to touch")
            .containsExactly(2L);
    }

    /**
     * #1194: a connector moved to another scope is askable there as soon as the write answers, and
     * the scope it left stops listing its table — whichever door moved it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"apply", "tags", "flat"})
    void a_connector_moved_to_another_scope_takes_its_table_from_the_old_card_to_the_new(String door) throws Exception {
        String table = "cidx_move_" + door + "_" + System.nanoTime();
        String from = "cidxfrom" + door;
        String to = "cidxto" + door;
        Path csv = Files.createTempFile(table + "_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x\n", StandardCharsets.UTF_8);
        String id = applyCsv(table, csv, from);
        assertThat(engine.runSync(UUID.fromString(id), new RunTrigger.Manual(1L)))
            .isInstanceOf(RunStatus.Completed.class);
        awaitCard(from, table, true);

        switch (door) {
            case "apply" -> applyCsv(table, csv, to);
            case "tags" -> given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
                .body(List.of(to))
                .when().put("/api/platform/connectors/" + id + "/tags")
                .then().statusCode(200);
            case "flat" -> {
                Map<String, Object> dto = new HashMap<>(given().headers(TestIdentity.tenantAndUser(1))
                    .when().get("/api/platform/connectors/" + id)
                    .then().statusCode(200).extract().jsonPath().getMap("$"));
                dto.put("tags", List.of(to));
                given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json").body(dto)
                    .when().put("/api/platform/connectors/" + id)
                    .then().statusCode(200);
            }
            default -> throw new IllegalArgumentException(door);
        }

        // no polling: the write itself rebuilt both cards
        assertThat(cardHasTable(1L, to, table)).as("the new scope's card lists the table").isTrue();
        assertThat(cardHasTable(1L, from, table)).as("the old scope's card no longer does").isFalse();
    }

    /** {@code lm apply} of a csv connector named {@code table}, reading {@code csv}, in {@code scope}; its id. */
    private String applyCsv(String table, Path csv, String scope) {
        String manifest = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: csv-file
              scopes: [%s]
            spec:
              transport:
                kind: local
                path: %s
              parser:
                has_header: true
              after_process: none
              target:
                table_prefix: t_
            """.formatted(table, scope, csv.toAbsolutePath());
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(manifest)
            .when().post("/api/platform/connectors/apply")
            .then().statusCode(200).extract().path("connectorId");
    }

    private List<Long> watermarkTenants(String table) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT tenant_id FROM core.search_projection_mark_watermark "
                 + "WHERE table_name = '" + table + "' ORDER BY tenant_id")) {
            List<Long> tenants = new ArrayList<>();
            while (rs.next()) tenants.add(rs.getLong(1));
            return tenants;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void awaitCard(String scope, String table, boolean holds) throws InterruptedException {
        for (int i = 0; i < 30 && cardHasTable(1L, scope, table) != holds; i++) Thread.sleep(1000);
        assertThat(cardHasTable(1L, scope, table)).as("card " + scope + " holds " + table).isEqualTo(holds);
    }

    /** A csv connector tagged {@code tag} whose one endpoint writes {@code body} into {@code t_<table>}. */
    private UUID csvConnector(String tag, String table, String body) throws Exception {
        Path csv = Files.createTempFile(table + "_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, body, StandardCharsets.UTF_8);
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L);
                c.setName(tag + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setTags(List.of(tag));
                c.setConfig(Map.of("kind", "csv-file",
                    "transport", Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath(csv.toAbsolutePath().toString()); ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
