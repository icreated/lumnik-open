package io.lumnik.hub.ask;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a completed connector run whose target is {@code hybrid} leave a scope the ask can answer?
 *
 * <p>{@link SchemaCardAutoRefreshIT} already proves the {@code connector.t_*} path does: a csv-file
 * run registers in {@code connector.dynamic_table}, SchemaCardRefresher sees the change and
 * rediscovers. The REST importer emits {@code target: {kind: hybrid, table: ext.…}} instead, which
 * writes through HybridJsonbWriter — and the quickstart's Scenario A ends on {@code lm ask}.
 *
 * <p>This is an EXPERIMENT with a control: it first proves the run really wrote rows through the
 * hybrid path, then asks whether the scope became answerable. Without the control, "no card" could
 * simply mean "nothing ran".
 */
@QuarkusTest
class HybridTargetSchemaCardIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject SchemaCardStore cardStore;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    static WireMockServer wm;

    @BeforeAll static void up()   { wm = new WireMockServer(0); wm.start(); configureFor("localhost", wm.port()); }
    @AfterAll  static void down() { if (wm != null) wm.stop(); }

    @Test
    void hybrid_run_and_the_scope_it_leaves_behind() throws Exception {
        String scope = "hybridprobe";
        String table = "ext.hybrid_card_" + Math.abs(System.nanoTime());
        String path  = "/v1/items";

        // offset pagination, proven working in RestConnectorRunRlsIT — the variable under test is
        // the TARGET being hybrid, so everything else is copied from a shape known to complete.
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("0")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":2,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("2")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":2,\"data\":[]}")));

        UUID connId = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L);
                c.setName("hybrid-card-" + System.nanoTime());
                c.setTypeId("rest-generic");
                c.setTags(List.of(scope));
                c.setConfig(Map.of("kind", "rest-generic", "base_url", "http://localhost:" + wm.port()));
                connectors.persist(c); connectors.flush();

                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("items");
                ep.setMethod("GET"); ep.setPath(path); ep.setEnabled(true);
                ep.setConfig(Map.of(
                    "pagination",  Map.of("kind", "offset", "offset_param", "offset",
                        "limit_param", "limit", "limit", 2, "total_path", "$.total",
                        "response_path", "$.data"),
                    "incremental", Map.of("mode", "full"),
                    "target", Map.of("kind", "hybrid", "table", table, "unique_key", "$.id",
                        "promote", List.of(Map.of("name", "name", "path", "$.name", "type", "text")))));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertThat(s).as("the run itself must complete").isInstanceOf(RunStatus.Completed.class);

        // THE CONTROL: the hybrid path really wrote. Without this, "no card" proves nothing.
        long rows;
        try (var con = adminDs.getConnection();
             var st = con.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next(); rows = rs.getLong(1);
        }
        assertThat(rows).as("the hybrid target must have received rows").isEqualTo(2L);

        // Now the question. The refresher is an async observer, so poll as the sibling IT does.
        boolean answerable = false;
        for (int i = 0; i < 20 && !answerable; i++) {
            answerable = TenantContext.callAsSystem(() ->
                cardStore.load(1L, scope).map(card -> !card.tables().isEmpty()).orElse(false));
            if (!answerable) Thread.sleep(1000);
        }

        String state = TenantContext.callAsSystem(() ->
            cardStore.load(1L, scope)
                .map(c -> "card with " + c.tables().size() + " table(s): "
                    + c.tables().stream().map(t -> t.name()).toList())
                .orElse("NO CARD AT ALL"));

        // The gap this test used to PIN is closed (C4/P18 PR1): HybridJsonbWriter registers its
        // ext.* table in connector.dynamic_table, the completed run triggers rediscover, and the
        // scope gains a card. The one-shot CSV path stays un-askable for its own reason — it
        // creates no run, so nothing ever triggers rediscover (CsvIngestServiceTest pins that).
        assertThat(answerable)
            .as("a completed hybrid run must leave an answerable scope — observed: %s", state)
            .isTrue();
    }
}
