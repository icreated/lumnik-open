package io.lumnik.hub.connector.rest.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
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
 * #1617: an endpoint's {@code mapping} runs the hooks, Kotlin included, over the promoted columns of each item — a
 * column a hook fills is declared by a {@code promote} entry without {@code path}, and {@code data} stays the item
 * the API returned.
 */
@QuarkusTest
class RestMappingHooksIT {

    private static final String PODS = "{\"items\":["
        + "{\"id\":\"a\",\"status\":{\"phase\":\"Running\",\"restarts\":7}},"
        + "{\"id\":\"b\",\"status\":{\"phase\":\"Running\",\"restarts\":0}},"
        + "{\"id\":\"c\",\"status\":{\"phase\":\"Pending\",\"restarts\":0}},"
        + "{\"id\":\"d\",\"status\":{\"restarts\":1}}]}";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRunRepository runs;
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
    void aKotlinScriptFillsADeclaredColumn_andDataStaysThePayload() {
        String table = table("prio");
        UUID id = seed(endpoint(table, "\"cell\":[{\"kotlin\":{\"script\":\"classpath:mapping/restart-priority.kts\","
            + "\"context\":{\"threshold\":5}}}]"));

        RunStatus s = run(id);

        assertThat(s).isInstanceOf(RunStatus.Completed.class);
        assertThat(rows("SELECT external_id || '=' || priority FROM " + table + " ORDER BY external_id"))
            .containsExactly("a=high", "b=none", "c=none", "d=none");
        assertThat(rows("SELECT data::text FROM " + table + " WHERE external_id = 'a'"))
            .as("data is the item as the API sent it: no promoted or computed column in it")
            .containsExactly("{\"id\": \"a\", \"status\": {\"phase\": \"Running\", \"restarts\": 7}}");
        assertThat(rows("SELECT scripts::text FROM connector.connector_run WHERE connector_id = '" + id + "'").getFirst())
            .as("the run says which script produced its rows (#1430)")
            .contains("classpath:mapping/restart-priority.kts");
    }

    @Test
    void anItemAHookDropsIsSkipped_andTheRunConservesItsRecords() {
        String table = table("drop");
        UUID id = seed(endpoint(table, "\"dataset\":[{\"filter-where\":{\"column\":\"phase\",\"equals\":\"Running\"}}]"));

        RunStatus s = run(id);

        assertThat(s).isInstanceOf(RunStatus.Completed.class);
        assertThat(rows("SELECT external_id FROM " + table + " ORDER BY external_id")).containsExactly("a", "b");
        ConnectorRun r = TenantContext.callAsSystem(() -> runs.findLatestActiveByConnector(id).orElseThrow());
        assertThat(List.of(r.getRecordsIn(), r.getRecordsOut(), r.getRecordsSkipped()))
            .as("records_in = records_out + records_skipped")
            .containsExactly(4L, 2L, 2L);
    }

    /** A field the item lacks is in the row, null — a hook that requires the column finds it (code review). */
    @Test
    void aFieldTheItemLacksIsInTheRow_null() {
        String table = table("gap");
        UUID id = seed(endpoint(table, "\"dataset\":[{\"require-headers\":{\"columns\":[\"phase\"]}}]"));

        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(rows("SELECT external_id FROM " + table + " WHERE phase IS NULL")).containsExactly("d");
    }

    /**
     * Under {@code on-error: dlq}, an item a hook cannot read is dead-lettered as the API sent it, and the cells its
     * promote could not read are not counted for it: the count says what was written (code review).
     */
    @Test
    void aDeadLetteredItemIsTheItem_andItsUnreadableCellsAreNotCounted() {
        String table = table("dlq");
        UUID id = seed(endpoint(table, "\"cell\":[{\"kotlin\":{\"script\":\"classpath:mapping/dlq-high-restarts.kts\"}}],"
            + "\"on-error\":{\"default\":\"dlq\"}").replace("{\"name\":\"priority\",\"type\":\"text\"}",
            "{\"name\":\"priority\",\"type\":\"text\"},"
            + "{\"name\":\"seen\",\"path\":\"$.status.phase\",\"type\":\"timestamptz\",\"transform\":\"parse_iso8601\"}"));

        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(rows("SELECT external_id FROM " + table + " ORDER BY external_id")).containsExactly("b", "c", "d");
        assertThat(rows("SELECT payload::text FROM connector.connector_dead_letter WHERE connector_id = '" + id + "'"))
            .containsExactly("{\"id\": \"a\", \"status\": {\"phase\": \"Running\", \"restarts\": 7}}");
        assertThat(rows("SELECT cell_errors::text FROM connector.connector_run WHERE connector_id = '" + id + "'"))
            .as("b and c have a phase parse_iso8601 cannot read; a's is not counted, a was not written")
            .containsExactly("2");
    }

    @Test
    void aColumnNoPromoteEntryDeclaresFailsTheRun_namingIt() {
        String table = table("undeclared");
        UUID id = seed(endpoint(table, "\"cell\":[{\"kotlin\":{\"script\":\"classpath:mapping/writes-undeclared.kts\"}}]"));

        RunStatus s = run(id);

        assertThat(s).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) s).message()).contains("'surprise'").contains("promote");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String table(String suffix) {
        return "ext.rest_map_" + suffix + "_" + Math.abs(System.nanoTime());
    }

    private static String endpoint(String table, String mapping) {
        return "{\"pagination\":{\"kind\":\"link-header\",\"response_path\":\"$.items\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":["
            + "{\"name\":\"phase\",\"path\":\"$.status.phase\",\"type\":\"text\"},"
            + "{\"name\":\"restarts\",\"path\":\"$.status.restarts\",\"type\":\"integer\"},"
            + "{\"name\":\"priority\",\"type\":\"text\"}]},"
            + "\"mapping\":{" + mapping + "}}";
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
