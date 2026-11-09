package io.lumnik.hub.connector.rest.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1855: the hybrid table keeps each item whole in its {@code jsonb} {@code data} column, which refuses U+0000 as
 * {@code text} does. An item holding one, anywhere in it, is dead-lettered alone — with or without a {@code mapping},
 * and even when a hook cleans the promoted cell, since {@code data} keeps the item as the API sent it.
 */
@QuarkusTest
class RestNulByteIT {

    private static final String PODS = "{\"items\":["
        + "{\"id\":\"a\",\"status\":{\"phase\":\"Running\"}},"
        + "{\"id\":\"b\",\"status\":{\"phase\":\"Run\\u0000ning\"},\"labels\":[\"x\",\"y\\u0000z\"]},"
        + "{\"id\":\"c\",\"status\":{\"phase\":\"Pending\"}}]}";

    @Inject ConnectorEngine engine;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
        stubFor(get(urlPathEqualTo("/pods")).willReturn(okJson(PODS)));
    }

    @Test
    void anItemHoldingANulByteIsDeadLetteredAlone_withoutAMapping() {
        String table = table("plain");
        UUID id = seed(endpoint(table, ""));

        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(rows("SELECT external_id FROM " + table + " ORDER BY external_id")).containsExactly("a", "c");
        assertThat(rows("SELECT error_message FROM connector.connector_dead_letter WHERE connector_id = '" + id + "'"))
            .singleElement().asString().contains("NUL byte");
        assertThat(rows("SELECT records_in || '=' || records_out || '+' || records_skipped"
            + " FROM connector.connector_run WHERE connector_id = '" + id + "'")).containsExactly("3=2+1");
    }

    @Test
    void anItemHoldingANulByteIsDeadLetteredAlone_whenAHookCleansThePromotedCell() {
        String table = table("clean");
        UUID id = seed(endpoint(table,
            ",\"mapping\":{\"cell\":[{\"clean\":{\"columns\":[\"phase\"],\"remove-control-chars\":true}}]}"));

        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(rows("SELECT external_id FROM " + table + " ORDER BY external_id")).containsExactly("a", "c");
        assertThat(rows("SELECT count(*)::text FROM connector.connector_dead_letter WHERE connector_id = '" + id + "'"))
            .containsExactly("1");
        assertThat(rows("SELECT records_in || '=' || records_out || '+' || records_skipped"
            + " FROM connector.connector_run WHERE connector_id = '" + id + "'")).containsExactly("3=2+1");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String table(String suffix) {
        return "ext.rest_nul_" + suffix + "_" + Math.abs(System.nanoTime());
    }

    /** @param mappingTail {@code ,"mapping":{…}} or empty */
    private static String endpoint(String table, String mappingTail) {
        return "{\"pagination\":{\"kind\":\"link-header\",\"response_path\":\"$.items\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"phase\",\"path\":\"$.status.phase\",\"type\":\"text\"}]}"
            + mappingTail + "}";
    }

    private RunStatus run(UUID id) {
        return TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
    }

    private UUID seed(String epJson) {
        UUID connId = UUID.randomUUID();
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("INSERT INTO connector.connector(id, tenant_id, org_id, name, type_id, config)"
                        + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId)
                    .setParameter("name", "rest-map-" + connId)
                    .setParameter("cfg", "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port() + "\"}")
                    .executeUpdate();
                em.createNativeQuery("INSERT INTO connector.connector_endpoint"
                        + "(id, connector_id, tenant_id, org_id, name, path, config)"
                        + " VALUES (:id, :connId, 1, 0, 'pods', '/pods', CAST(:cfg AS jsonb))")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("connId", connId)
                    .setParameter("cfg", epJson)
                    .executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
        return connId;
    }

    @SuppressWarnings("unchecked")
    private List<String> rows(String sql) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                List<String> found = (List<String>) em.createNativeQuery(sql).getResultList();
                utx.commit();
                return found;
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw e;
            }
        });
    }
}
