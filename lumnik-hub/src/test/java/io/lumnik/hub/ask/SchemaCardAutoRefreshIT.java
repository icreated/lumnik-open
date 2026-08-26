package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
}
